package io.mionav.pack

import android.database.sqlite.SQLiteDatabase

/**
 * Read-only MBTiles access. MBTiles is plain SQLite, so the watch needs no custom tile parser —
 * the same reason the design picked it over PMTiles.
 */
class TileStore(path: String) : AutoCloseable {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        // MBTiles uses TMS: flip the y axis.
        val tmsY = (1 shl z) - 1 - y
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), tmsY.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getBlob(0)
        }
    }

    fun metadata(): Map<String, String> = buildMap {
        db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
            while (c.moveToNext()) put(c.getString(0), c.getString(1))
        }
    }

    override fun close() = db.close()

    companion object {
        /** Called once after import: checks the three numbers the manifest promises. */
        fun verify(path: String, expectedTiles: Int): Boolean {
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT count(*) FROM tiles", null).use { c ->
                    return c.moveToFirst() && c.getInt(0) == expectedTiles
                }
            }
        }
    }
}
