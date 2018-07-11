package org.retest.rebazer.service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand.Operation;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.CannotDeleteCurrentBranchException;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.retest.rebazer.config.RebazerConfig;
import org.retest.rebazer.config.RebazerConfig.RepositoryConfig;
import org.retest.rebazer.config.RebazerConfig.RepositoryHost;
import org.retest.rebazer.config.RebazerConfig.Team;
import org.retest.rebazer.domain.PullRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RebaseService {

	private final RebazerConfig config;

	private int currentGcCountdown;

	private final Map<String, CredentialsProvider> repoCredentials = new HashMap<>();
	private final Map<String, Git> repoGit = new HashMap<>();
	private final Map<String, String> repoUrl = new HashMap<>();

	@Autowired
	public RebaseService( final RebazerConfig config ) {
		this.config = config;
		currentGcCountdown = config.getGarbageCollectionCountdown();
		config.getHosts().forEach( host -> {
			host.getTeams().forEach( team -> {
				team.getRepos().forEach( repo -> {
					repoCredentials.put( repo.getName(),
							new UsernamePasswordCredentialsProvider( team.getUser(), team.getPass() ) );
					final Git localRepo = createUrl( config, host, team, repoCredentials.get( repo.getName() ), repo );
					repoGit.put( repo.getName(), localRepo );
					cleanUp( repo );
				} );
			} );
		} );
	}

	private Git createUrl( final RebazerConfig config, final RepositoryHost host, final Team team,
			CredentialsProvider credentials, final RepositoryConfig repo ) {
		credentials = repoCredentials.get( repo.getName() );
		final File repoFolder = new File( config.getWorkspace(), repo.getName() );
		Git localRepo = null;
		repoUrl.put( repo.getName(), host.getUrl() + team.getName() + "/" + repo.getName() + ".git" );
		return localRepo = checkRepoFolder( credentials, repoFolder, localRepo, repoUrl.get( repo.getName() ) );
	}

	private Git checkRepoFolder( final CredentialsProvider credentials, final File repoFolder, Git localRepo,
			final String repoUrl ) {
		if ( repoFolder.exists() ) {
			localRepo = tryToOpenExistingRepoAndCheckRemote( repoFolder, repoUrl );
			if ( localRepo == null ) {
				try {
					FileUtils.deleteDirectory( repoFolder );
				} catch ( final IOException e ) {
					throw new RuntimeException( e );
				}
			}
		}
		if ( localRepo == null ) {
			localRepo = cloneNewRepo( repoFolder, repoUrl, credentials );
		}
		return localRepo;
	}

	private static Git tryToOpenExistingRepoAndCheckRemote( final File repoFolder, final String expectedRepoUrl ) {
		try {
			final Git repo = Git.open( repoFolder );
			if ( originIsRepoUrl( repo, expectedRepoUrl ) ) {
				return repo;
			} else {
				log.error( "Repo has wrong remote URL!" );
				return null;
			}
		} catch ( final Exception e ) {
			log.error( "Exception while open repo!", e );
			return null;
		}
	}

	@SneakyThrows
	private static boolean originIsRepoUrl( final Git repo, final String expectedRepoUrl ) {
		return repo.remoteList().call().stream().anyMatch( r -> r.getName().equals( "origin" )
				&& r.getURIs().stream().anyMatch( url -> url.toString().equals( expectedRepoUrl ) ) );
	}

	@SneakyThrows
	private static Git cloneNewRepo( final File repoFolder, final String gitRepoUrl,
			final CredentialsProvider credentialsProvider ) {
		log.info( "Cloning repository {} to folder {} ...", gitRepoUrl, repoFolder );
		return Git.cloneRepository().setURI( gitRepoUrl ).setCredentialsProvider( credentialsProvider )
				.setDirectory( repoFolder ).call();
	}

	@SneakyThrows
	public boolean rebase( final RepositoryConfig repo, final PullRequest pullRequest ) {
		log.info( "Rebasing {}.", pullRequest );

		try {
			final Git repository = repoGit.get( repo.getName() );
			final CredentialsProvider credentials = repoCredentials.get( repo.getName() );
			repository.fetch().setCredentialsProvider( credentials ).setRemoveDeletedRefs( true ).call();
			repository.checkout().setCreateBranch( true ).setName( pullRequest.getSource() )
					.setStartPoint( "origin/" + pullRequest.getSource() ).call();

			final RebaseResult rebaseResult =
					repository.rebase().setUpstream( "origin/" + pullRequest.getDestination() ).call();

			switch ( rebaseResult.getStatus() ) {
				case UP_TO_DATE:
					log.warn( "Why rebasing up to date {}?", pullRequest );
					return true;

				case FAST_FORWARD:
					log.warn( "Why creating {} without changes?", pullRequest );
					// fall-through

				case OK:
					repository.push().setCredentialsProvider( credentials ).setForce( true ).call();
					return true;

				case STOPPED:
					log.info( "Merge conflict in {}", pullRequest );
					repository.rebase().setOperation( Operation.ABORT ).call();
					return false;

				default:
					repository.rebase().setOperation( Operation.ABORT ).call();
					throw new RuntimeException(
							"For " + pullRequest + " rebase causes an unexpected result: " + rebaseResult.getStatus() );
			}
		} finally {
			cleanUp( repo );
		}
	}

	@SneakyThrows
	private void cleanUp( final RepositoryConfig repo ) {
		final Git repository = repoGit.get( repo.getName() );
		resetAndRemoveUntrackedFiles( repository );
		repository.checkout().setName( "remotes/origin/" + repo.getBranch() ).call();
		removeAllLocalBranches( repository );
		triggerGc( repository );
	}

	private void resetAndRemoveUntrackedFiles( final Git repository )
			throws GitAPIException, CheckoutConflictException {
		repository.clean() //
				.setCleanDirectories( true ) //
				.setForce( true ) //
				.setIgnore( false ) //
				.call(); //

		repository.reset() //
				.setMode( ResetType.HARD ) //
				.call(); //
	}

	private void removeAllLocalBranches( final Git repository )
			throws GitAPIException, NotMergedException, CannotDeleteCurrentBranchException {
		final String[] localBranches = repository.branchList() //
				.call().stream() //
				.filter( r -> r.getName() //
						.startsWith( "refs/heads/" ) ) //
				.map( r -> r.getName() ) //
				.toArray( String[]::new ); //

		repository.branchDelete() //
				.setForce( true ) //
				.setBranchNames( localBranches ) //
				.call(); //
	}

	private void triggerGc( final Git repository ) throws GitAPIException {
		currentGcCountdown--;
		if ( currentGcCountdown == 0 ) {
			currentGcCountdown = config.getGarbageCollectionCountdown();
			log.info( "Running git gc on {}, next gc after {} rebases.", repository, currentGcCountdown );
			repository.gc() //
					.setPrunePreserved( true ) //
					.setExpire( null ) //
					.call(); //
		}
	}

}
