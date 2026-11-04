package be.lain.bloom.di

import android.content.Context
import androidx.room.Room
import be.lain.bloom.data.db.BloomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): BloomDatabase =
        Room.databaseBuilder(context, BloomDatabase::class.java, "bloom.db").build()

    @Provides @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()
}
