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

import org.jetbrains.kotlin.load.java.AbiVersionUtil
import org.jetbrains.kotlin.serialization.jvm.JvmPackageTable
import java.io.ByteArrayInputStream
import java.io.DataInputStream

public class ModuleMapping(val packageFqName2Parts: Map<String, PackageParts>) {

    fun findPackageParts(packageFqName: String): PackageParts? {
        return packageFqName2Parts[packageFqName]
    }

    companion object {
        public val MAPPING_FILE_EXT: String = "kotlin_module";

        public val EMPTY: ModuleMapping = ModuleMapping(emptyMap());

        fun create(protoWithAbi: ByteArray? = null): ModuleMapping {
            if (protoWithAbi == null) {
                return EMPTY
            }

            val inputStream = DataInputStream(ByteArrayInputStream(protoWithAbi))
            val abiVersion = Integer.valueOf(inputStream.readUTF());
            if (AbiVersionUtil.isAbiVersionCompatible(abiVersion)) {
                val parseFrom: JvmPackageTable.PackageTable? = JvmPackageTable.PackageTable.parseFrom(inputStream)
                if (parseFrom != null) {
                    val packageFqNameParts = hashMapOf<String, PackageParts>()
                    parseFrom.packagePartsList.map {
                        val packageParts = PackageParts(it.packageFqName)
                        packageFqNameParts.put(it.packageFqName, packageParts)
                        it.classNameList.map {
                            packageParts.parts.add(it)
                        }
                    }
                    return ModuleMapping(packageFqNameParts)
                }
            }
            return EMPTY
        }
    }
}

public class PackageParts(val packageFqName: String) {

    val parts = linkedSetOf<String>()

    override fun equals(other: Any?) =
            other is PackageParts && other.packageFqName == packageFqName && other.parts == parts

    override fun hashCode() =
            packageFqName.hashCode() * 31 + parts.hashCode()

    companion object {
        @jvmStatic public fun PackageParts.serialize(builder: JvmPackageTable.PackageTable.Builder) {
            if (this.parts.isNotEmpty()) {
                val packageParts = JvmPackageTable.PackageParts.newBuilder()
                packageParts.setPackageFqName(this.packageFqName)
                packageParts.addAllClassName(this.parts.sorted())
                builder.addPackageParts(packageParts)
            }
        }
    }
}

