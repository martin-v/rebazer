package org.retest.rebazer.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.retest.rebazer.config.RebazerConfig;
import org.retest.rebazer.config.RebazerConfig.Repository;
import org.retest.rebazer.domain.PullRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor( onConstructor = @__( @Autowired ) )
public class BitbucketService {

	private final RestTemplate bitbucketTemplate;
	private final RestTemplate bitbucketLegacyTemplate;

	private final RebazerConfig config;
	private final RebaseService rebaseService;
	private final PullRequestLastUpdateStore pullRequestLastUpdateStore;

	@Scheduled( fixedDelay = 60 * 1000 )
	public void pollBitbucket() {
		for ( final Repository repo : config.getRepos() ) {
			log.debug( "Processing {}.", repo );
			for ( final PullRequest pr : getAllPullRequests( repo ) ) {
				handlePR( repo, pr );
			}
		}
	}

	private void handlePR( final Repository repo, final PullRequest pullRequest ) {
		log.debug( "Processing {}.", pullRequest );

		if ( pullRequestLastUpdateStore.isHandled( repo, pullRequest ) ) {
			log.info( "{} is unchanged since last run (last change: {}).", pullRequest,
					pullRequestLastUpdateStore.getLastDate( repo, pullRequest ) );

		} else if ( !greenBuildExists( pullRequest ) ) {
			log.info( "Waiting for green build of {}.", pullRequest );
			pullRequestLastUpdateStore.setHandled( repo, pullRequest );

		} else if ( rebaseNeeded( pullRequest ) ) {
			rebase( repo, pullRequest );
			// we need to update the "lastUpdate" of a PullRequest to counteract if addComment is called
			pullRequestLastUpdateStore.setHandled( repo, getLatestUpdate( pullRequest ) );

		} else if ( !isApproved( pullRequest ) ) {
			log.info( "Waiting for approval of {}.", pullRequest );
			pullRequestLastUpdateStore.setHandled( repo, pullRequest );

		} else {
			log.info( "Merging pull request " + pullRequest );
			merge( pullRequest );
			pullRequestLastUpdateStore.resetAllInThisRepo( repo );
		}
	}

	PullRequest getLatestUpdate( final PullRequest pullRequest ) {
		final DocumentContext jp = jsonPathForPath( pullRequest.getUrl() );
		final PullRequest updatedPullRequest = PullRequest.builder() //
				.id( pullRequest.getId() ) //
				.repo( pullRequest.getRepo() ) //
				.source( pullRequest.getSource() ) //
				.destination( pullRequest.getDestination() ) //
				.url( pullRequest.getUrl() ) //
				.lastUpdate( jp.read( "$.updated_on" ) ) //
				.build();
		return updatedPullRequest;
	}

	boolean isApproved( final PullRequest pullRequest ) {
		final DocumentContext jp = jsonPathForPath( pullRequest.getUrl() );
		return jp.<List<Boolean>> read( "$.participants[*].approved" ).stream().anyMatch( approved -> approved );
	}

	boolean rebaseNeeded( final PullRequest pullRequest ) {
		return !getLastCommonCommitId( pullRequest ).equals( getHeadOfBranch( pullRequest ) );
	}

	String getHeadOfBranch( final PullRequest pullRequest ) {
		final String url = "/repositories/" + config.getTeam() + "/" + pullRequest.getRepo() + "/";
		return jsonPathForPath( url + "refs/branches/" + pullRequest.getDestination() ).read( "$.target.hash" );
	}

	String getLastCommonCommitId( final PullRequest pullRequest ) {
		DocumentContext jp = jsonPathForPath( pullRequest.getUrl() + "/commits" );

		final int pageLength = jp.read( "$.pagelen" );
		final int size = jp.read( "$.size" );
		final int lastPage = (pageLength + size - 1) / pageLength;

		if ( lastPage > 1 ) {
			jp = jsonPathForPath( pullRequest.getUrl() + "/commits?page=" + lastPage );
		}

		final List<String> commitIds = jp.read( "$.values[*].hash" );
		final List<String> parentIds = jp.read( "$.values[*].parents[0].hash" );

		return parentIds.stream().filter( parent -> !commitIds.contains( parent ) ).findFirst()
				.orElseThrow( IllegalStateException::new );
	}

	private void merge( final PullRequest pullRequest ) {
		final String message = String.format( "Merged in %s (pull request #%d) by ReBaZer", pullRequest.getSource(),
				pullRequest.getId() );
		// TODO add approver to message?
		final Map<String, Object> request = new HashMap<>();
		request.put( "close_source_branch", true );
		request.put( "message", message );
		request.put( "merge_strategy", "merge_commit" );

		bitbucketTemplate.postForObject( pullRequest.getUrl() + "/merge", request, Object.class );
	}

	boolean greenBuildExists( final PullRequest pullRequest ) {
		final DocumentContext jp = jsonPathForPath( pullRequest.getUrl() + "/statuses" );
		final int size = jp.read( "$.size" );
		if ( size > 0 ) {
			return jp.<List<String>> read( "$.values[*].state" ).stream().anyMatch( s -> s.equals( "SUCCESSFUL" ) );
		}
		return true;
	}

	List<PullRequest> getAllPullRequests( final Repository repo ) {
		final String urlPath = "/repositories/" + config.getTeam() + "/" + repo.getName() + "/pullrequests";
		final DocumentContext jp = jsonPathForPath( urlPath );
		return parsePullRequestsJson( repo, urlPath, jp );
	}

	private static List<PullRequest> parsePullRequestsJson( final Repository repo, final String urlPath,
			final DocumentContext jp ) {
		final int numPullRequests = (int) jp.read( "$.size" );
		final List<PullRequest> results = new ArrayList<>( numPullRequests );
		for ( int i = 0; i < numPullRequests; i++ ) {
			final int id = jp.read( "$.values[" + i + "].id" );
			final String source = jp.read( "$.values[" + i + "].source.branch.name" );
			final String destination = jp.read( "$.values[" + i + "].destination.branch.name" );
			final String lastUpdate = jp.read( "$.values[" + i + "].updated_on" );
			results.add( PullRequest.builder() //
					.id( id ) //
					.repo( repo.getName() ) //
					.source( source ) //
					.destination( destination ) //
					.url( urlPath + "/" + id ) //
					.lastUpdate( lastUpdate ) //
					.build() ); //
		}
		return results;
	}

	DocumentContext jsonPathForPath( final String urlPath ) {
		final String json = bitbucketTemplate.getForObject( urlPath, String.class );
		return JsonPath.parse( json );
	}

	private void rebase( final Repository repo, final PullRequest pullRequest ) {
		if ( !rebaseService.rebase( repo, pullRequest ) ) {
			addComment( pullRequest );
		}
	}

	private void addComment( final PullRequest pullRequest ) {
		final Map<String, String> request = new HashMap<>();
		request.put( "content", "This pull request needs some manual love ..." );
		bitbucketLegacyTemplate.postForObject( pullRequest.getUrl() + "/comments", request, String.class );
	}

}
