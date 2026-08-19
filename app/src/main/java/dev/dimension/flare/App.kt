package dev.dimension.flare

import android.app.Application

/**
 * Android process entry point for FlareDo.
 *
 * Keep initialization that genuinely requires an Android [Application] context here. Portable forum
 * services and their dependency graph belong to the shared modules so Android, desktop, and Apple hosts
 * observe the same behavior.
 */
class App : Application()
