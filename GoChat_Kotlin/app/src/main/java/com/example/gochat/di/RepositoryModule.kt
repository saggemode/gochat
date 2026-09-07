package com.example.gochat.di

import com.example.gochat.data.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    // If we had interfaces, we'd use @Binds here.
    // Since we use concrete classes with @Inject constructor, Hilt handles it.
    // For now, this module can be empty if all repositories are properly annotated.
}
