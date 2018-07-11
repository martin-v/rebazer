package org.retest.rebazer.service;

import java.util.HashMap;
import java.util.Map;

import org.retest.rebazer.config.RebazerConfig.RepositoryConfig;
import org.retest.rebazer.domain.PullRequest;
import org.springframework.stereotype.Component;

@Component
public class PullRequestLastUpdateStore {

	private final Map<RepositoryConfig, Map<Integer, String>> pullRequestUpdateStates = new HashMap<>();

	void setHandled( final RepositoryConfig repo, final PullRequest pullRequest ) {
		Map<Integer, String> repoMap = pullRequestUpdateStates.get( repo );
		if ( repoMap == null ) {
			repoMap = new HashMap<>();
			pullRequestUpdateStates.put( repo, repoMap );
		}
		repoMap.put( pullRequest.getId(), pullRequest.getLastUpdate() );
	}

	String getLastDate( final RepositoryConfig repo, final PullRequest pullRequest ) {
		final Map<Integer, String> repoMap = pullRequestUpdateStates.get( repo );
		return repoMap != null ? repoMap.get( pullRequest.getId() ) : null;
	}

	void resetAllInThisRepo( final RepositoryConfig repo ) {
		final Map<Integer, String> repoMap = pullRequestUpdateStates.get( repo );
		if ( repoMap != null ) {
			repoMap.clear();
		}
	}

	boolean isHandled( final RepositoryConfig repo, final PullRequest pullRequest ) {
		return pullRequest.getLastUpdate().equals( getLastDate( repo, pullRequest ) );
	}

}
