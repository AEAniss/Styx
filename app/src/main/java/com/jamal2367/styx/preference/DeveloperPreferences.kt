package com.jamal2367.styx.preference

import com.jamal2367.styx.di.DevPrefs
import com.jamal2367.styx.preference.delegates.booleanPreference
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences related to development debugging.
 *
 * Created by anthonycr on 2/19/18.
 */
@Singleton
class DeveloperPreferences @Inject constructor(
    @DevPrefs preferences: SharedPreferences
) {

    var checkedForTor by preferences.booleanPreference(INITIAL_CHECK_FOR_TOR, false)

    var checkedForI2P by preferences.booleanPreference(INITIAL_CHECK_FOR_I2P, false)
}

private const val INITIAL_CHECK_FOR_TOR = "checkForTor"
private const val INITIAL_CHECK_FOR_I2P = "checkForI2P"
