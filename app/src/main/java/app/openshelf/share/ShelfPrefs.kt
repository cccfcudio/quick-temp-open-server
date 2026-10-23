package app.openshelf.share

import android.content.Context
import app.openshelf.core.browse.FileKind
import app.openshelf.core.browse.Listing
import app.openshelf.core.browse.ListingOptions
import app.openshelf.core.browse.SortDirection
import app.openshelf.core.browse.SortKey
import app.openshelf.core.json.JsonValue
import app.openshelf.core.json.MiniJson
import app.openshelf.core.json.asObject
import app.openshelf.core.json.asString
import app.openshelf.core.model.ShareDefaults
import app.openshelf.core.model.StoredMount
import app.openshelf.core.model.StoredMountKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ShelfPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mountsState = MutableStateFlow(readMounts())
    private val passwordState = MutableStateFlow(prefs.getString(KEY_PASSWORD, "") ?: "")
    private val portState = MutableStateFlow(prefs.getInt(KEY_PORT, ShareDefaults.PORT))
    private val listingState = MutableStateFlow(readListing())

    val mounts: StateFlow<List<StoredMount>> = mountsState.asStateFlow()
    val password: StateFlow<String> = passwordState.asStateFlow()
    val port: StateFlow<Int> = portState.asStateFlow()
    val listing: StateFlow<ListingOptions> = listingState.asStateFlow()

    fun currentMounts(): List<StoredMount> = mountsState.value

    fun currentPassword(): String = passwordState.value

    fun currentPort(): Int = portState.value

    fun currentListing(): ListingOptions = listingState.value

    fun setListing(options: ListingOptions) {
        val stored = options.copy(query = "")
        prefs.edit()
            .putString(KEY_SORT, stored.sort.name)
            .putString(KEY_ORDER, stored.direction.name)
            .putString(KEY_KIND, stored.kind?.name ?: "")
            .putInt(KEY_PAGE_SIZE, Listing.normalizePageSize(stored.pageSize))
            .apply()
        listingState.value = stored
    }

    fun tunnelWanted(): Boolean = prefs.getBoolean(KEY_TUNNEL, false)

    fun setPassword(value: String) {
        prefs.edit().putString(KEY_PASSWORD, value).apply()
        passwordState.value = value
    }

    fun setPort(value: Int) {
        val port = value.coerceIn(1, 65535)
        prefs.edit().putInt(KEY_PORT, port).apply()
        portState.value = port
    }

    fun setTunnelWanted(wanted: Boolean) {
        prefs.edit().putBoolean(KEY_TUNNEL, wanted).apply()
    }

    fun add(mount: StoredMount) {
        val next = currentMounts().filterNot { it.id == mount.id || it.location == mount.location } + mount
        writeMounts(next)
    }

    fun remove(id: String) {
        writeMounts(currentMounts().filterNot { it.id == id })
    }

    private fun writeMounts(mounts: List<StoredMount>) {
        prefs.edit().putString(KEY_MOUNTS, encode(mounts)).apply()
        mountsState.value = mounts
    }

    private fun readMounts(): List<StoredMount> {
        val raw = prefs.getString(KEY_MOUNTS, null) ?: return emptyList()
        return decode(raw)
    }

    private fun encode(mounts: List<StoredMount>): String {
        val items = mounts.map { mount ->
            MiniJson.obj(
                "id" to MiniJson.str(mount.id),
                "displayName" to MiniJson.str(mount.displayName),
                "location" to MiniJson.str(mount.location),
                "kind" to MiniJson.str(mount.kind.name),
            )
        }
        return MiniJson.stringify(MiniJson.arr(items))
    }

    private fun decode(raw: String): List<StoredMount> {
        val parsed = try {
            MiniJson.parse(raw)
        } catch (error: IllegalArgumentException) {
            return emptyList()
        }
        val array = (parsed as? JsonValue.Arr)?.values ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val id = obj["id"]?.asString() ?: return@mapNotNull null
            val name = obj["displayName"]?.asString() ?: return@mapNotNull null
            val location = obj["location"]?.asString() ?: return@mapNotNull null
            val kind = parseKind(obj["kind"]?.asString() ?: return@mapNotNull null) ?: return@mapNotNull null
            StoredMount(id, name, location, kind)
        }
    }

    private fun readListing(): ListingOptions {
        return ListingOptions(
            sort = parseSort(prefs.getString(KEY_SORT, null)),
            direction = parseOrder(prefs.getString(KEY_ORDER, null)),
            kind = parseFileKind(prefs.getString(KEY_KIND, null)),
            pageSize = Listing.normalizePageSize(prefs.getInt(KEY_PAGE_SIZE, 50)),
        )
    }

    private fun parseSort(raw: String?): SortKey = when (raw) {
        SortKey.SIZE.name -> SortKey.SIZE
        SortKey.MODIFIED.name -> SortKey.MODIFIED
        else -> SortKey.NAME
    }

    private fun parseOrder(raw: String?): SortDirection = when (raw) {
        SortDirection.DESC.name -> SortDirection.DESC
        else -> SortDirection.ASC
    }

    private fun parseFileKind(raw: String?): FileKind? = when (raw) {
        FileKind.IMAGE.name -> FileKind.IMAGE
        FileKind.VIDEO.name -> FileKind.VIDEO
        FileKind.DOCUMENT.name -> FileKind.DOCUMENT
        FileKind.OTHER.name -> FileKind.OTHER
        else -> null
    }

    private fun parseKind(raw: String): StoredMountKind? = when (raw) {
        StoredMountKind.SAF_TREE.name -> StoredMountKind.SAF_TREE
        StoredMountKind.APP_DIRECTORY.name -> StoredMountKind.APP_DIRECTORY
        else -> null
    }

    private companion object {
        const val FILE = "openshelf"
        const val KEY_MOUNTS = "mounts"
        const val KEY_PASSWORD = "password"
        const val KEY_PORT = "port"
        const val KEY_TUNNEL = "tunnel_wanted"
        const val KEY_SORT = "list_sort"
        const val KEY_ORDER = "list_order"
        const val KEY_KIND = "list_kind"
        const val KEY_PAGE_SIZE = "list_page_size"
    }
}
