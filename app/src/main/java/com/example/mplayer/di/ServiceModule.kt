package com.example.mplayer.di

import android.content.Context
import com.example.mplayer.data.remote.MusicDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

@Module
@InstallIn(ServiceComponent::class)
object ServiceModule {

    @ServiceScoped
    @Provides
    fun provideMusicDatabase() = MusicDatabase()

//    @ServiceScoped
//    @Provides
//    fun provideAudioAttributes() = AudioAttributes.Builder()
//        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
//        .setUsage(C.USAGE_MEDIA)
//        .build()
//
//    @ServiceScoped
//    @Provides
//    fun provideExoPlayer(
//        @ApplicationContext context: Context,
//        audioAttributes: AudioAttributes
//    ) = SimpleExoPlayer.Builder(context).build().apply {
//        setAudioAttributes(audioAttributes, true)
//        setHandleAudioBecomingNoisy(true)
//    }
//
//    @ServiceScoped
//    @Provides
//    fun provideDataSourceFactory(
//        @ApplicationContext context: Context
//    ) = DefaultDataSourceFactory(context, Util.getUserAgent(context, "Mplayer App"))
}