package com.nononsenseapps.feeder.background

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendModeStore
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.model.notify
import com.nononsenseapps.feeder.model.opml.importOpml
import com.nononsenseapps.feeder.util.ToastMaker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bind
import org.kodein.di.instance
import java.util.UUID
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class BackendSyncGuardsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = app.getSharedPreferences("sync_guard_${UUID.randomUUID()}", 0)
    private val modes = BackendModeStore(prefs)
    private val messages = mutableListOf<Int>()
    private var systemCalls = 0
    private val toast =
        object : ToastMaker {
            override suspend fun makeToast(text: String) {
                error("Unexpected error: $text")
            }

            override suspend fun makeToast(resId: Int) {
                messages += resId
            }
        }
    private val fixtureDi =
        DI {
            bind<BackendModeStore>() with instance(modes)
            bind<ToastMaker>() with instance(toast)
        }
    private val context =
        object : ContextWrapper(app), DIAware {
            override val di = fixtureDi

            override fun getApplicationContext(): Context = this

            override fun getSystemService(name: String): Any? {
                systemCalls++
                return super.getSystemService(name)
            }
        }

    @After fun cleanup() {
        prefs.edit().clear().commit()
    }

    @Test fun currentMinifluxModeBlocksRssNotificationEvenForAnOlderLocalSnapshot() =
        runBlocking {
            val coordinator = BackendSyncCoordinator { modes.selection.value }
            coordinator.withRun(BackendSelection()) {
                modes.commit(BackendSelection(BackendMode.MINIFLUX, "a".repeat(64)))
                notify(context)
                notify(context, updateSummaryOnly = true)
                assertEquals(0, systemCalls)
            }
        }

    @Test fun externalOpmlImportIsBlockedBeforeAnyParserOrContentResolverIsResolved() =
        runBlocking {
            modes.commit(BackendSelection(BackendMode.MINIFLUX, "a".repeat(64)))
            importOpml(fixtureDi, Uri.parse("content://fixture/never-opened"))
            assertEquals(listOf(R.string.miniflux_main_local_only), messages)
            assertEquals(0, systemCalls)
        }
}
