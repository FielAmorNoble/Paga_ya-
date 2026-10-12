package com.example.pagaya

import android.app.Application

class PagaYaApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
