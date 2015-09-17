/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.load.kotlin

import org.jetbrains.kotlin.serialization.ClassData
import org.jetbrains.kotlin.serialization.PackageData

interface ProtoCache {
    fun getClassData(klass: KotlinJvmBinaryClass, load: () -> ClassData): ClassData
    fun getPackageData(klass: KotlinJvmBinaryClass, load: () -> PackageData): PackageData

    object Default : ProtoCache {
        override fun getClassData(klass: KotlinJvmBinaryClass, load: () -> ClassData) = load()
        override fun getPackageData(klass: KotlinJvmBinaryClass, load: () -> PackageData) = load()
    }

    companion object {
        val INSTANCE = run {
            if (System.getProperty("kotlin.disable.proto.cache") == "true") {
                Default
            }
            else try {
                Class.forName("org.jetbrains.kotlin.load.kotlin.ProtoCacheImpl").newInstance() as ProtoCache
            }
            catch (e: Throwable) {
                System.err.println("Could not load org.jetbrains.kotlin.load.kotlin.ProtoCacheImpl: ${e.getMessage()}")
                e.printStackTrace()
                Default
            }
        }
    }
}
