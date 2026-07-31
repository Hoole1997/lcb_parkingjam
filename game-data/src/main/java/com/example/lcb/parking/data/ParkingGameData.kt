package com.example.lcb.parking.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.lcb.parking.data.level.AssetLevelSource
import com.example.lcb.parking.data.state.PreferencesGameStateStore
import com.example.lcb.parking.domain.ports.GameStateStore
import com.example.lcb.parking.domain.ports.LevelSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Data 层唯一组合入口。调用方无需知道 assets 路径、DataStore 文件名或协程生命周期。
 */
object ParkingGameData {

    private val lock = Any()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var levelSourceInstance: LevelSource? = null

    @Volatile
    private var gameStateStoreInstance: GameStateStore? = null

    fun levelSource(context: Context): LevelSource {
        levelSourceInstance?.let { return it }
        return synchronized(lock) {
            levelSourceInstance ?: AssetLevelSource(context.applicationContext.assets).also {
                levelSourceInstance = it
            }
        }
    }

    /**
     * 同一进程、同一文件只创建一个 DataStore，避免界面重建触发多实例访问异常。
     */
    fun gameStateStore(context: Context): GameStateStore {
        gameStateStoreInstance?.let { return it }
        return synchronized(lock) {
            gameStateStoreInstance ?: createGameStateStore(context.applicationContext).also {
                gameStateStoreInstance = it
            }
        }
    }

    private fun createGameStateStore(applicationContext: Context): GameStateStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { applicationContext.preferencesDataStoreFile(DATA_STORE_FILE_NAME) },
        )
        return PreferencesGameStateStore(dataStore)
    }

    private const val DATA_STORE_FILE_NAME = "parking_game_state"
}
