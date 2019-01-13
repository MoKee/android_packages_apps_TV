/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tv.app;

import android.content.Context;
import android.content.Intent;
import com.android.tv.TvActivity;
import com.android.tv.TvApplication;
import com.android.tv.TvSingletons;
import com.android.tv.analytics.Analytics;
import com.android.tv.analytics.StubAnalytics;
import com.android.tv.analytics.Tracker;
import com.android.tv.common.actions.InputSetupActionUtils;
import com.android.tv.common.experiments.ExperimentLoader;
import com.android.tv.common.flags.impl.DefaultBackendKnobsFlags;
import com.android.tv.common.flags.impl.DefaultCloudEpgFlags;
import com.android.tv.common.flags.impl.DefaultConcurrentDvrPlaybackFlags;
import com.android.tv.common.singletons.HasSingletons;
import com.android.tv.common.util.CommonUtils;
import com.android.tv.data.epg.EpgReader;
import com.android.tv.data.epg.StubEpgReader;
import com.android.tv.modules.TvSingletonsModule;
import com.android.tv.perf.PerformanceMonitor;
import com.android.tv.perf.PerformanceMonitorManagerFactory;
import com.android.tv.tuner.setup.LiveTvTunerSetupActivity;
import com.android.tv.tunerinputcontroller.BuiltInTunerManager;
import com.android.tv.util.account.AccountHelper;
import com.android.tv.util.account.AccountHelperImpl;
import com.google.common.base.Optional;
import javax.inject.Provider;

/** The top level application for Live TV. */
public class LiveTvApplication extends TvApplication implements HasSingletons<TvSingletons> {

    static {
        PERFORMANCE_MONITOR_MANAGER.getStartupMeasure().onAppClassLoaded();
    }

    private final Provider<EpgReader> mEpgReaderProvider =
            new Provider<EpgReader>() {

                @Override
                public EpgReader get() {
                    return new StubEpgReader(LiveTvApplication.this);
                }
            };

    private final DefaultBackendKnobsFlags mBackendKnobsFlags = new DefaultBackendKnobsFlags();
    private final DefaultCloudEpgFlags mCloudEpgFlags = new DefaultCloudEpgFlags();
    private final DefaultConcurrentDvrPlaybackFlags mConcurrentDvrPlaybackFlags =
            new DefaultConcurrentDvrPlaybackFlags();
    private AccountHelper mAccountHelper;
    private Analytics mAnalytics;
    private Tracker mTracker;
    private String mEmbeddedInputId;
    private ExperimentLoader mExperimentLoader;
    private PerformanceMonitor mPerformanceMonitor;

    @Override
    public void onCreate() {
        super.onCreate();
        DaggerLiveTvApplicationComponent.builder()
                .tvSingletonsModule(new TvSingletonsModule(this))
                .build()
                .inject(this);
        PERFORMANCE_MONITOR_MANAGER.getStartupMeasure().onAppCreate(this);
    }

    /** Returns the {@link AccountHelperImpl}. */
    @Override
    public AccountHelper getAccountHelper() {
        if (mAccountHelper == null) {
            mAccountHelper = new AccountHelperImpl(getApplicationContext());
        }
        return mAccountHelper;
    }

    @Override
    public synchronized PerformanceMonitor getPerformanceMonitor() {
        if (mPerformanceMonitor == null) {
            mPerformanceMonitor = PerformanceMonitorManagerFactory.create().initialize(this);
        }
        return mPerformanceMonitor;
    }

    @Override
    public Provider<EpgReader> providesEpgReader() {
        return mEpgReaderProvider;
    }

    @Override
    public ExperimentLoader getExperimentLoader() {
        mExperimentLoader = new ExperimentLoader();
        return mExperimentLoader;
    }

    @Override
    public DefaultBackendKnobsFlags getBackendKnobs() {
        return mBackendKnobsFlags;
    }

    /** Returns the {@link Analytics}. */
    @Override
    public synchronized Analytics getAnalytics() {
        if (mAnalytics == null) {
            mAnalytics = StubAnalytics.getInstance(this);
        }
        return mAnalytics;
    }

    /** Returns the default tracker. */
    @Override
    public synchronized Tracker getTracker() {
        if (mTracker == null) {
            mTracker = getAnalytics().getDefaultTracker();
        }
        return mTracker;
    }

    @Override
    public Intent getTunerSetupIntent(Context context) {
        // Make an intent to launch the setup activity of TV tuner input.
        Intent intent =
                CommonUtils.createSetupIntent(
                        new Intent(context, LiveTvTunerSetupActivity.class), mEmbeddedInputId);
        intent.putExtra(InputSetupActionUtils.EXTRA_INPUT_ID, mEmbeddedInputId);
        Intent tvActivityIntent = new Intent(context, TvActivity.class);

        intent.putExtra(InputSetupActionUtils.EXTRA_ACTIVITY_AFTER_COMPLETION, tvActivityIntent);
        return intent;
    }

    @Override
    public DefaultCloudEpgFlags getCloudEpgFlags() {
        return mCloudEpgFlags;
    }

    @Override
    public Optional<BuiltInTunerManager> getBuiltInTunerManager() {
        return Optional.absent();
    }

    @Override
    public BuildType getBuildType() {
        return BuildType.AOSP;
    }

    @Override
    public DefaultConcurrentDvrPlaybackFlags getConcurrentDvrPlaybackFlags() {
        return mConcurrentDvrPlaybackFlags;
    }

    @Override
    public TvSingletons singletons() {
        return this;
    }
}
