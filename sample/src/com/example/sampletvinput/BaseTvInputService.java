/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sampletvinput;

import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Programs;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.KeyEvent;
import android.view.Surface;

import java.io.IOException;
import java.util.List;

abstract public class BaseTvInputService extends TvInputService {
    private static final String TAG = "BaseTvInputService";
    private static final boolean DEBUG = true;

    private final LongSparseArray<ChannelInfo> mChannelMap = new LongSparseArray<ChannelInfo>();
    private final Handler mProgramUpdateHandler = new Handler();

    protected List<ChannelInfo> mChannels;
    private boolean mAvailable = true;

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "onCreate()");
        super.onCreate();

        buildChannelMap();
        // TODO: Uncomment or remove when a new API design is locked down.
        // setAvailable(mAvailable);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy()");
        super.onDestroy();
    }

    @Override
    public TvInputService.Session onCreateSession() {
        if (DEBUG) Log.d(TAG, "onCreateSession()");
        return new BaseTvInputSessionImpl();
    }

    abstract public List<ChannelInfo> createSampleChannels();

    private synchronized void buildChannelMap() {
        Uri uri = TvContract.buildChannelsUriForInput(new ComponentName(this, this.getClass()),
                false);
        String[] projection = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER
        };
        mChannels = createSampleChannels();
        if (mChannels == null || mChannels.isEmpty()) {
            Log.w(TAG, "No channel list.");
            return;
        }
        Cursor cursor = null;
        try {
            do {
                cursor = getContentResolver().query(uri, projection, null, null, null);
                if (cursor != null && cursor.getCount() > 0) {
                    break;
                }
                if (DEBUG) Log.d(TAG, "Couldn't find the channel list. Inserting new channels...");
                // Insert channels into the database. This needs to be done only for the first time.
                ChannelUtils.populateChannels(this, this.getClass().getName(), mChannels);
            } while (true);

            while (cursor.moveToNext()) {
                long channelId = cursor.getLong(0);
                String channelNumber = cursor.getString(1);
                if (DEBUG) Log.d(TAG, "Channel mapping: ID(" + channelId + ") -> " + channelNumber);
                mChannelMap.put(channelId, getChannelByNumber(channelNumber, false));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private ChannelInfo getChannelByNumber(String channelNumber, boolean isRetry) {
        for (ChannelInfo info : mChannels) {
            if (info.mNumber.equals(channelNumber)) {
                return info;
            }
        }
        if (!isRetry) {
            buildChannelMap();
            return getChannelByNumber(channelNumber, true);
        }
        throw new IllegalArgumentException("Unknown channel: " + channelNumber);
    }

    private ChannelInfo getChannelByUri(Uri channelUri, boolean isRetry) {
        ChannelInfo info = mChannelMap.get(ContentUris.parseId(channelUri));
        if (info == null) {
            if (!isRetry) {
                buildChannelMap();
                return getChannelByUri(channelUri, true);
            }
            throw new IllegalArgumentException("Unknown channel: " + channelUri);
        }
        return info;
    }

    class BaseTvInputSessionImpl extends TvInputService.Session {
        private MediaPlayer mPlayer;
        private float mVolume;
        private boolean mMute;

        protected BaseTvInputSessionImpl() {
            mPlayer = new MediaPlayer();
            mVolume = 1.0f;
            mMute = false;
        }

        @Override
        public void onRelease() {
            if (mPlayer != null) {
                mPlayer.release();
                mPlayer = null;
            }
        }

        @Override
        public boolean onSetSurface(Surface surface) {
            if (DEBUG) Log.d(TAG, "onSetSurface(" + surface + ")");
            mPlayer.setSurface(surface);
            return true;
        }

        @Override
        public void onSetStreamVolume(float volume) {
            if (DEBUG) Log.d(TAG, "onSetStreamVolume(" + volume + ")");
            mVolume = volume;
            mPlayer.setVolume(volume, volume);
        }

        private boolean setDataSource(MediaPlayer player, ChannelInfo channel) {
            ProgramInfo program = channel.mProgram;
            try {
                if (program.mUrl != null) {
                    player.setDataSource(program.mUrl);
                } else {
                    AssetFileDescriptor afd = getResources().openRawResourceFd(program.mResourceId);
                    if (afd == null) {
                        return false;
                    }
                    player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(),
                            afd.getDeclaredLength());
                    afd.close();
                }
                if (program.mUrl == null || !program.mUrl.startsWith("http")) {
                    // TODO: Android media player does not support looping for HLS. Find a way to
                    //     loop every contents.
                    player.setLooping(true);
                }
            } catch (IllegalArgumentException | IllegalStateException | IOException e) {
                // Do nothing.
            }
            return true;
        }

        @Override
        public boolean onTune(Uri channelUri) {
            if (DEBUG) Log.d(TAG, "tune(" + channelUri + ")");

            final ChannelInfo channel = getChannelByUri(channelUri, false);
            mPlayer.reset();
            if (!setDataSource(mPlayer, channel)) {
                if (DEBUG) Log.d(TAG, "Failed to set the data source");
                return false;
            }
            try {
                mPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer player) {
                        if (mPlayer != null && !mPlayer.isPlaying()) {
                            int duration = mPlayer.getDuration();
                            if (duration > 0) {
                                long currentTimeMs = System.currentTimeMillis();
                                mPlayer.seekTo((int) (currentTimeMs % duration));
                            }
                            mPlayer.start();
                        }
                    }
                });
                mPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override
                    public void onVideoSizeChanged(MediaPlayer player, int width, int height) {
                        if (mPlayer != null) {
                            dispatchVideoStreamChanged(channel.mVideoWidth, channel.mVideoHeight,
                                    false);
                            dispatchAudioStreamChanged(channel.mAudioChannel);
                            dispatchClosedCaptionStreamChanged(channel.mHasClosedCaption);
                        }
                    }
                });
                mPlayer.prepareAsync();
            } catch (IllegalStateException e1) {
                return false;
            }

            // Create empty program information and insert it into the database.
            // Delay intentionally to see whether the updated program information dynamically
            // replaces the previous one on the channel banner (for testing). This is to simulate
            // the actual case where we get parsed program data only after tuning is done.
            final long DELAY_FOR_TESTING_IN_MILLIS = 1000; // 1 second
            mProgramUpdateHandler.postDelayed(
                    new AddProgramRunnable(channelUri, channel.mProgram),
                    DELAY_FOR_TESTING_IN_MILLIS);
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_M) {
                mMute = !mMute;
                if (mMute) {
                    mPlayer.setVolume(0.0f, 0.0f);
                } else {
                    mPlayer.setVolume(mVolume, mVolume);
                }
                return true;
            } else if (event.getKeyCode() == KeyEvent.KEYCODE_A) {
                // It simulates availability changes such as HDMI cable plug-off/plug-in.
                // The availability is toggled whenever 'a' key is dispatched from a TV app.
                mAvailable = !mAvailable;
                // TODO: Uncomment or remove when a new API design is locked down.
                // setAvailable(mAvailable);
                return true;
            }
            return false;
        }

        private class AddProgramRunnable implements Runnable {
            private static final int PROGRAM_REPEAT_COUNT = 24;
            private final Uri mChannelUri;
            private final ProgramInfo mProgram;

            public AddProgramRunnable(Uri channelUri, ProgramInfo program) {
                mChannelUri = channelUri;
                mProgram = program;
            }

            @Override
            public void run() {
                if (mProgram.mDurationSec == 0) {
                    return;
                }
                long nowSec = System.currentTimeMillis() / 1000;
                long startTimeSec = nowSec
                        - positiveMod((nowSec - mProgram.mStartTimeSec), mProgram.mDurationSec);
                ContentValues values = new ContentValues();
                values.put(Programs.COLUMN_CHANNEL_ID, ContentUris.parseId(mChannelUri));
                values.put(Programs.COLUMN_TITLE, mProgram.mTitle);
                values.put(Programs.COLUMN_SHORT_DESCRIPTION, mProgram.mDescription);
                if (!TextUtils.isEmpty(mProgram.mPosterArtUri)) {
                    values.put(Programs.COLUMN_POSTER_ART_URI, mProgram.mPosterArtUri);
                }

                for (int i = 0; i < PROGRAM_REPEAT_COUNT; ++i) {
                    if (!hasProgramInfo((startTimeSec + i * mProgram.mDurationSec + 1) * 1000)) {
                        values.put(Programs.COLUMN_START_TIME_UTC_MILLIS,
                                (startTimeSec + i * mProgram.mDurationSec) * 1000);
                        values.put(Programs.COLUMN_END_TIME_UTC_MILLIS,
                                (startTimeSec + (i + 1) * mProgram.mDurationSec) * 1000);
                        getContentResolver().insert(TvContract.Programs.CONTENT_URI, values);
                    }
                }
            }

            private long positiveMod(long x, long modulo) {
                return ((x % modulo) + modulo)  % modulo;
            }

            private boolean hasProgramInfo(long timeMs) {
                Uri uri = TvContract.buildProgramsUriForChannel(mChannelUri, timeMs, timeMs);
                String[] projection = { TvContract.Programs._ID };
                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(uri, projection, null, null, null);
                    if (cursor.getCount() > 0) {
                        return true;
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                return false;
            }
        }
    }

    public static final class ChannelInfo {
        public final String mNumber;
        public final String mName;
        public final String mLogoUrl;
        public final int mVideoWidth;
        public final int mVideoHeight;
        public final int mAudioChannel;
        public final boolean mHasClosedCaption;
        public final ProgramInfo mProgram;

        public ChannelInfo(String number, String name, String logoUrl, int videoWidth,
                int videoHeight, int audioChannel, boolean hasClosedCaption, ProgramInfo program) {
            mNumber = number;
            mName = name;
            mLogoUrl = logoUrl;
            mVideoWidth = videoWidth;
            mVideoHeight = videoHeight;
            mAudioChannel = audioChannel;
            mHasClosedCaption = hasClosedCaption;
            mProgram = program;
        }
    }

    public static final class ProgramInfo {
        public final String mTitle;
        public final String mPosterArtUri;
        public final String mDescription;
        public final long mStartTimeSec;
        public final long mDurationSec;
        public final String mUrl;
        public final int mResourceId;

        public ProgramInfo(String title, String posterArtUri, String description, long startTimeSec,
                long durationSec, String url, int resourceId) {
            mTitle = title;
            mPosterArtUri = posterArtUri;
            mDescription = description;
            mStartTimeSec = startTimeSec;
            mDurationSec = durationSec;
            mUrl = url;
            mResourceId = resourceId;
        }
    }
}
