package be.lain.bloom

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.lain.bloom.data.db.BloomDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every schema version must migrate to the latest without losing data. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BloomDatabase::class.java)

    @Test fun migratesFromVersion1KeepingWeights() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO weight_entry(date, at, kg, source, externalId) VALUES (20000, 0, 85.0, 'MANUAL', NULL)")
            close()
        }
        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BloomDatabase::class.java, dbName).build()
        val weights = runBlocking { db.bodyDao().weights() }
        assertEquals(1, weights.size)
        assertEquals(85.0, weights.single().kg, 0.0)
        db.close()
    }
}
