package com.epaper.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.epaper.reader.data.AppDatabase

fun res(path: String): ByteArray = object {}.javaClass.getResourceAsStream(path)!!.use { it.readBytes() }

fun memoryDb(): AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
    .allowMainThreadQueries().build()
