package be.lain.bloom.di

import be.lain.bloom.data.ai.TrainingContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope for work that must outlive a screen (AI conversations, sync). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    /** Training features contribute context for the AI coach. */
    @Multibinds abstract fun trainingContexts(): Set<TrainingContext>

    companion object {
        @Provides @Singleton @AppScope
        fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
