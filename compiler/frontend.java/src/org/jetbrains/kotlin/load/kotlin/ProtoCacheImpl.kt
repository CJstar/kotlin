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
import java.util.*

class ProtoCacheImpl : ProtoCache {
    private val classDatas = HashMap<String, Data<ClassData>>()
    private val packageDatas = HashMap<String, Data<PackageData>>()

    private class Data<T>(val timeStamp: Long, val proto: T)

    override fun getClassData(klass: KotlinJvmBinaryClass, load: () -> ClassData) =
            getData(klass, classDatas, load)

    override fun getPackageData(klass: KotlinJvmBinaryClass, load: () -> PackageData) =
            getData(klass, packageDatas, load)

    private fun getData<T>(klass: KotlinJvmBinaryClass, cache: MutableMap<String, Data<T>>, load: () -> T): T {
        val virtualFile = (klass as? VirtualFileKotlinClass)?.file ?: return load()

        val key = virtualFile.url
        val timeStamp = virtualFile.timeStamp

        val cached = cache[key]
        if (cached != null && cached.timeStamp == timeStamp) {
            return cached.proto
        }

        return load().apply {
            cache[key] = Data(timeStamp, this)
        }
    }
}
