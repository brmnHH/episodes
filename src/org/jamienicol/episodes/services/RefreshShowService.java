/*
 * Copyright (C) 2013 Jamie Nicol <jamie@thenicols.net>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jamienicol.episodes.services;

import android.app.IntentService;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import java.util.List;
import org.jamienicol.episodes.db.EpisodesTable;
import org.jamienicol.episodes.db.ShowsTable;
import org.jamienicol.episodes.db.ShowsProvider;
import org.jamienicol.episodes.tvdb.Client;
import org.jamienicol.episodes.tvdb.Episode;
import org.jamienicol.episodes.tvdb.Show;

public class RefreshShowService extends IntentService
{
	private static final String TAG = "RefreshShowService";

	public RefreshShowService() {
		super("RefreshShowService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		Client tvdbClient = new Client("25B864A8BC56AFAD");

		final int showId = intent.getIntExtra("showId", 0);

		Log.i(TAG, String.format("Refreshing show %d", showId));

		final int showTvdbId = getShowTvdbId(showId);
		// fetch full show + episode information from tvdb
		Show show = tvdbClient.getShow(showTvdbId);

		updateShow(showId, show);
		updateExistingEpisodes(showId, show.getEpisodes());
		addNewEpisodes(showId, show.getEpisodes());
	}

	private int getShowTvdbId(int showId) {
		Uri showUri = Uri.withAppendedPath(ShowsProvider.CONTENT_URI_SHOWS,
		                                   new Integer(showId).toString());
		final String[] projection = {
			ShowsTable.COLUMN_TVDB_ID
		};

		Cursor showCursor =
			getContentResolver().query(showUri,
			                           projection,
			                           null,
			                           null,
			                           null);

		int tvdbIdColumnIndex =
			showCursor.getColumnIndexOrThrow(ShowsTable.COLUMN_TVDB_ID);
		showCursor.moveToFirst();

		return showCursor.getInt(tvdbIdColumnIndex);
	}

	private void updateShow(int showId, Show show) {
		ContentValues showValues = new ContentValues();
		showValues.put(ShowsTable.COLUMN_TVDB_ID, show.getId());
		showValues.put(ShowsTable.COLUMN_NAME, show.getName());
		showValues.put(ShowsTable.COLUMN_OVERVIEW, show.getOverview());
		if (show.getFirstAired() != null) {
			showValues.put(ShowsTable.COLUMN_FIRST_AIRED,
			               show.getFirstAired().getTime() / 1000);
		}

		Uri showUri = Uri.withAppendedPath(ShowsProvider.CONTENT_URI_SHOWS,
		                                   new Integer(showId).toString());
		getContentResolver().update(showUri, showValues, null, null);
	}

	private void updateExistingEpisodes(int showId, List<Episode> episodes) {
		Cursor episodesCursor = getEpisodesCursor(showId);

		while (episodesCursor.moveToNext()) {

			final int idColumnIndex =
				episodesCursor.getColumnIndexOrThrow(EpisodesTable.COLUMN_ID);
			final int episodeId = episodesCursor.getInt(idColumnIndex);
			final Uri episodeUri =
				Uri.withAppendedPath(ShowsProvider.CONTENT_URI_EPISODES,
				                     new Integer(episodeId).toString());

			final int tvdbIdColumnIndex =
				episodesCursor.getColumnIndexOrThrow(EpisodesTable.COLUMN_TVDB_ID);
			final int episodeTvdbId = episodesCursor.getInt(tvdbIdColumnIndex);
			Episode episode = findEpisodeWithTvdbId(episodes,
			                                        episodeTvdbId);

			if (episode == null) {
				/* the episode no longer exists in tvdb. delete */
				Log.i(TAG, String.format("Deleting episode %d: no longer exists in tvdb.", episodeId));
				getContentResolver().delete(episodeUri, null, null);

			} else {
				/* update the episode row with the new values */
				ContentValues epValues = new ContentValues();
				epValues.put(EpisodesTable.COLUMN_TVDB_ID, episode.getId());
				epValues.put(EpisodesTable.COLUMN_SHOW_ID, showId);
				epValues.put(EpisodesTable.COLUMN_NAME, episode.getName());
				epValues.put(EpisodesTable.COLUMN_OVERVIEW,
				             episode.getOverview());
				epValues.put(EpisodesTable.COLUMN_EPISODE_NUMBER,
				             episode.getEpisodeNumber());
				epValues.put(EpisodesTable.COLUMN_SEASON_NUMBER,
				             episode.getSeasonNumber());
				if (episode.getFirstAired() != null) {
					epValues.put(EpisodesTable.COLUMN_FIRST_AIRED,
					             episode.getFirstAired().getTime() / 1000);
				}

				Log.i(TAG, String.format("Updating episode %d.", episodeId));
				getContentResolver().update(episodeUri, epValues, null, null);

				/* remove episode from list of episodes
				 * returned by tvdb. by the end of this function
				 * this list will only contain new episodes */
				episodes.remove(episode);
			}
		}
	}

	private Cursor getEpisodesCursor(int showId) {
		final String[] projection = {
			EpisodesTable.COLUMN_ID,
			EpisodesTable.COLUMN_TVDB_ID
		};
		final String selection = String.format("%s=?",
		                                       EpisodesTable.COLUMN_SHOW_ID);
		final String[] selectionArgs = {
			new Integer(showId).toString()
		};

		Cursor cursor =
			getContentResolver().query(ShowsProvider.CONTENT_URI_EPISODES,
			                           projection,
			                           selection,
			                           selectionArgs,
			                           null);

		return cursor;
	}

	private Episode findEpisodeWithTvdbId(List<Episode> episodes,
	                                      int episodeTvdbId) {
		for (Episode ep : episodes) {
			if (ep.getId() == episodeTvdbId) {
				return ep;
			}
		}

		return null;
	}

	private void addNewEpisodes(int showId, List<Episode> episodes) {

		for (Episode episode : episodes) {
			ContentValues epValues = new ContentValues();
			epValues.put(EpisodesTable.COLUMN_TVDB_ID, episode.getId());
			epValues.put(EpisodesTable.COLUMN_SHOW_ID, showId);
			epValues.put(EpisodesTable.COLUMN_NAME, episode.getName());
			epValues.put(EpisodesTable.COLUMN_OVERVIEW,
			             episode.getOverview());
			epValues.put(EpisodesTable.COLUMN_EPISODE_NUMBER,
			             episode.getEpisodeNumber());
			epValues.put(EpisodesTable.COLUMN_SEASON_NUMBER,
			             episode.getSeasonNumber());
			if (episode.getFirstAired() != null) {
				epValues.put(EpisodesTable.COLUMN_FIRST_AIRED,
				             episode.getFirstAired().getTime() / 1000);
			}

			Log.i(TAG, "Adding new episode.");
			getContentResolver().insert(ShowsProvider.CONTENT_URI_EPISODES,
			                            epValues);
		}
	}
}
