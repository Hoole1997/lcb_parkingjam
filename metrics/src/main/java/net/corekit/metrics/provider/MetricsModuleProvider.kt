package net.corekit.metrics.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import net.corekit.metrics.log.MetricsLogger

/**
 * 分析统计模块内容提供者
 * 用于在模块初始化时获取 Context 并初始化 AdjustController
 */
class MetricsModuleProvider : ContentProvider() {
    
    companion object {
        private var applicationContext: android.content.Context? = null
        
        /**
         * 获取应用上下文
         */
        fun getApplicationContext(): android.content.Context? = applicationContext
    }
    
    override fun onCreate(): Boolean {
        applicationContext = context?.applicationContext
        // A ContentProvider runs before Application.onCreate. It may retain only application
        // context here; constructing network-backed reporters is deferred until privacy consent.
        MetricsLogger.d("MetricsModuleProvider context ready")

        return true
    }
    
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null
    
    override fun getType(uri: Uri): String? = null
    
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
