package org.retest.rebazer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.retest.rebazer.config.RebazerConfig;
import org.retest.rebazer.connector.RepositoryConnector;
import org.retest.rebazer.domain.PullRequest;
import org.retest.rebazer.domain.RepositoryConfig;
import org.retest.rebazer.service.PullRequestLastUpdateStore;
import org.retest.rebazer.service.RebaseService;
import org.springframework.boot.web.client.RestTemplateBuilder;

@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class RebazerServiceTest {

	RebazerService cut;

	@Mock
	RebaseService rebaseService;
	@Mock
	RebazerConfig rebazerConfig;
	@Mock
	PullRequestLastUpdateStore pullRequestLastUpdateStore;
	@Mock
	RestTemplateBuilder templateBuilder;
	@Mock
	RepositoryConfig repoConfig;
	@Mock
	PullRequest pullRequest;
	@Mock
	RepositoryConnector repoConnector;

	@BeforeEach
	void setUp() {
		when( rebazerConfig.getBranchBlacklist() ).thenReturn( new RebazerConfig().getBranchBlacklist() );
		when( pullRequest.getSource() ).thenReturn( "feature/foo" );
		cut = spy( new RebazerService( rebaseService, rebazerConfig, pullRequestLastUpdateStore, templateBuilder ) );
	}

	@Test
	void pollToHandleAllPullRequests_call_handleRepo_foreach_repo() {
		final RepositoryConfig repoConfig1 = mock( RepositoryConfig.class );
		final RepositoryConfig repoConfig2 = mock( RepositoryConfig.class );
		when( repoConfig.getConnector( templateBuilder ) ).thenReturn( repoConnector );
		when( repoConfig1.getConnector( templateBuilder ) ).thenReturn( repoConnector );
		when( repoConfig2.getConnector( templateBuilder ) ).thenReturn( repoConnector );
		when( repoConnector.getAllPullRequests() ).thenReturn( new ArrayList<>() );
		when( rebazerConfig.getRepos() ).thenReturn( Arrays.asList( repoConfig, repoConfig1, repoConfig2 ) );

		cut.pollToHandleAllPullRequests();

		verify( rebazerConfig ).getRepos();
		verify( cut ).handleRepo( repoConfig );
		verify( cut ).handleRepo( repoConfig1 );
		verify( cut ).handleRepo( repoConfig2 );
		verify( cut ).pollToHandleAllPullRequests();
		verifyNoMoreInteractions( cut, rebazerConfig );
	}

	@Test
	void pollToHandleAllPullRequests_catch_Exception_and_continue() {
		final RepositoryConfig repoConfig1 = mock( RepositoryConfig.class );
		final RepositoryConfig repoConfig2 = mock( RepositoryConfig.class );
		when( repoConfig.getConnector( templateBuilder ) ).thenReturn( repoConnector );
		when( repoConfig1.getConnector( templateBuilder ) ).thenReturn( repoConnector );
		when( repoConfig2.getConnector( templateBuilder ) ).thenReturn( repoConnector );
		when( repoConnector.getAllPullRequests() ).thenThrow( RuntimeException.class ); // changed
		when( rebazerConfig.getRepos() ).thenReturn( Arrays.asList( repoConfig, repoConfig1, repoConfig2 ) );

		cut.pollToHandleAllPullRequests();

		verify( rebazerConfig ).getRepos();
		verify( cut ).handleRepo( repoConfig );
		verify( cut ).handleRepo( repoConfig1 );
		verify( cut ).handleRepo( repoConfig2 );
		verify( cut ).pollToHandleAllPullRequests();
		verifyNoMoreInteractions( cut, rebazerConfig );
	}

	@Test
	void handleRepo_call_handlePullRequest_foreach_PR() {
		final PullRequest pullRequest1 = mock( PullRequest.class );
		when( pullRequest1.getSource() ).thenReturn( "feature/bar" );
		final PullRequest pullRequest2 = mock( PullRequest.class );
		when( pullRequest2.getSource() ).thenReturn( "feature/baz" );
		when( repoConfig.getConnector( templateBuilder ) ).thenReturn( repoConnector );
		when( repoConnector.getAllPullRequests() )
				.thenReturn( Arrays.asList( pullRequest, pullRequest1, pullRequest2 ) );

		cut.handleRepo( repoConfig );

		verify( cut ).handlePullRequest( repoConnector, repoConfig, pullRequest );
		verify( cut ).handlePullRequest( repoConnector, repoConfig, pullRequest1 );
		verify( cut ).handlePullRequest( repoConnector, repoConfig, pullRequest2 );
		verify( cut ).handleRepo( repoConfig );
		verifyNoMoreInteractions( cut, templateBuilder );
	}

	@Test
	void handlePullRequest_test() {
		when( repoConnector.greenBuildExists( pullRequest ) ).thenReturn( true );
		when( repoConnector.isApproved( pullRequest ) ).thenReturn( true );

		cut.handlePullRequest( repoConnector, repoConfig, pullRequest );

		verify( repoConnector ).merge( pullRequest );
		verify( repoConnector ).isApproved( pullRequest );
		verify( repoConnector ).rebaseNeeded( pullRequest );
		verify( pullRequestLastUpdateStore ).resetAllInThisRepo( repoConfig );
		verify( repoConnector ).greenBuildExists( pullRequest );
		verify( cut ).handlePullRequest( repoConnector, repoConfig, pullRequest );
		verifyNoMoreInteractions( cut, pullRequestLastUpdateStore, repoConnector );
	}

	@Test
	void handlePullRequest_rebase_needed() {
		when( repoConnector.greenBuildExists( pullRequest ) ).thenReturn( true );
		when( repoConnector.rebaseNeeded( pullRequest ) ).thenReturn( true );
		when( rebaseService.rebase( repoConfig, pullRequest ) ).thenReturn( false );

		cut.handlePullRequest( repoConnector, repoConfig, pullRequest );

		verify( repoConnector ).addComment( Mockito.any( PullRequest.class ), Mockito.anyString() );
		verify( repoConnector ).greenBuildExists( pullRequest );
		verify( repoConnector ).rebaseNeeded( pullRequest );
		verify( pullRequestLastUpdateStore ).setHandled( repoConfig, null );
		verify( repoConnector ).getLatestUpdate( pullRequest );
		verify( cut ).handlePullRequest( repoConnector, repoConfig, pullRequest );
		verifyNoMoreInteractions( cut, pullRequestLastUpdateStore, repoConnector );
	}

	@Test
	void handlePullRequest_isChangeDetection_and_isHandled() {
		when( rebazerConfig.isChangeDetection() ).thenReturn( true );
		when( pullRequestLastUpdateStore.isHandled( repoConfig, pullRequest ) ).thenReturn( true );

		cut.handlePullRequest( repoConnector, repoConfig, pullRequest );

		verify( pullRequestLastUpdateStore ).getLastDate( repoConfig, pullRequest );
		verify( pullRequestLastUpdateStore ).isHandled( repoConfig, pullRequest );
		verify( cut ).handlePullRequest( repoConnector, repoConfig, pullRequest );
		verifyNoMoreInteractions( cut, pullRequestLastUpdateStore );
	}

	@Test
	void handlePullRequest_greenBuildExists_false() {
		cut.handlePullRequest( repoConnector, repoConfig, pullRequest );

		verify( repoConnector ).greenBuildExists( pullRequest );
		verify( pullRequestLastUpdateStore ).setHandled( repoConfig, repoConnector.getLatestUpdate( pullRequest ) );
		verify( cut ).handlePullRequest( repoConnector, repoConfig, pullRequest );
		verifyNoMoreInteractions( cut, pullRequestLastUpdateStore );
	}

	@Test
	void handlePullRequest_isApproved_false() {
		when( repoConnector.greenBuildExists( pullRequest ) ).thenReturn( true );

		cut.handlePullRequest( repoConnector, repoConfig, pullRequest );

		verify( pullRequestLastUpdateStore ).setHandled( repoConfig, pullRequest );
		verify( cut ).handlePullRequest( repoConnector, repoConfig, pullRequest );
		verifyNoMoreInteractions( cut, pullRequestLastUpdateStore );
	}

	@Test
	void blacklisted_branches_should_be_ignored() {
		cut.handlePullRequest( repoConnector, repoConfig, pullRequest );
		verify( repoConnector, times( 1 ) ).greenBuildExists( pullRequest );

		final PullRequest pullRequest1 = mock( PullRequest.class );
		when( pullRequest1.getSource() ).thenReturn( "release/bar" );
		cut.handlePullRequest( repoConnector, repoConfig, pullRequest1 );
		verify( repoConnector, never() ).greenBuildExists( pullRequest1 );
	}
}
