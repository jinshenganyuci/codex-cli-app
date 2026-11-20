package com.nanci.codexremote.test

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** Verifies speed capabilities, request parameters and local migration with fixture data. */
class ModelSpeedInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        val result = runCatching {
            report.putStringArrayList("core", ArrayList(CoreStateFixChecks.run()))
            report.putStringArrayList("preferences", ArrayList(ConnectionIdentityChecks.run(targetContext)))
        }
        result.exceptionOrNull()?.let { report.putString("failure", it.stackTraceToString()) }
        report.putString("status", if (result.isSuccess) "passed" else "failed")
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}
