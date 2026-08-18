package cn.a10miaomiao.bilidown.common.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

object DataStoreKeys {

    val appPackageNameSet = stringSetPreferencesKey("app_package_name_set")

    val enabledShizuku = booleanPreferencesKey("enabled_shizuku")

    // 导出成功后是否删除哔哩缓存源文件（默认关闭，需用户显式开启）
    val exportDeleteSource = booleanPreferencesKey("export_delete_source")
}