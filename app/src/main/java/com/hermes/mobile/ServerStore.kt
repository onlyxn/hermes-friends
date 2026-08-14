package com.hermes.mobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 服务器配置: 一个 Hermes 内核 */
data class ServerConfig(
    val id: String,          // 唯一 id,用于 cookie 隔离
    var name: String,        // 自定义显示名
    var baseUrl: String,     // 服务地址
    var username: String = "",
    var cookie: String = "", // 登录 cookie(每服务器独立)
    var loggedIn: Boolean = false
)

/** 多服务器存储: SharedPreferences JSON 列表 */
class ServerStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hermes_servers", Context.MODE_PRIVATE)

    private val KEY_SERVERS = "servers"

    fun loadAll(): MutableList<ServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        val list = mutableListOf<ServerConfig>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ServerConfig(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "Hermes"),
                        baseUrl = o.optString("baseUrl", ""),
                        username = o.optString("username", ""),
                        cookie = o.optString("cookie", ""),
                        loggedIn = o.optBoolean("loggedIn", false)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    fun saveAll(list: List<ServerConfig>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("baseUrl", s.baseUrl)
                    put("username", s.username)
                    put("cookie", s.cookie)
                    put("loggedIn", s.loggedIn)
                }
            )
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    fun add(server: ServerConfig) {
        val list = loadAll()
        list.add(server)
        saveAll(list)
    }

    fun update(server: ServerConfig) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) {
            list[idx] = server
            saveAll(list)
        }
    }

    fun remove(id: String) {
        val list = loadAll().filter { it.id != id }.toMutableList()
        saveAll(list)
    }

    fun get(id: String): ServerConfig? = loadAll().firstOrNull { it.id == id }
}
