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

package org.jetbrains.kotlin.load.kotlin;

import com.intellij.ide.highlighter.JavaClassFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KotlinBinaryClassCache implements Disposable {
    private static class CachedResult {
        public final long modificationStamp;
        public final VirtualFileKotlinClass kotlinClass;

        public CachedResult(long modificationStamp, @Nullable VirtualFileKotlinClass kotlinClass) {
            this.modificationStamp = modificationStamp;
            this.kotlinClass = kotlinClass;
        }
    }

    private final Map<String, CachedResult> map = new ConcurrentHashMap<String, CachedResult>();

    @Nullable
    public static KotlinJvmBinaryClass getKotlinBinaryClass(@NotNull final VirtualFile file) {
        if (file.getFileType() != JavaClassFileType.INSTANCE) return null;

        KotlinBinaryClassCache service = ServiceManager.getService(KotlinBinaryClassCache.class);
        Map<String, CachedResult> map = service.map;

        String url = file.getUrl();
        long timeStamp = file.getModificationStamp();

        CachedResult result = map.get(url);
        if (result != null && result.modificationStamp == timeStamp) {
            VirtualFileKotlinClass cachedClass = result.kotlinClass;
            if (cachedClass != null) {
                if (file == cachedClass.getFile()) {
                    return cachedClass;
                }
                else {
                    return new VirtualFileKotlinClass(
                            file, cachedClass.getClassId(), cachedClass.getClassHeader(), cachedClass.getInnerClasses()
                    );
                }
            }
        }

        VirtualFileKotlinClass kotlinClass = ApplicationManager.getApplication().runReadAction(new Computable<VirtualFileKotlinClass>() {
            @SuppressWarnings("deprecation")
            @Override
            public VirtualFileKotlinClass compute() {
                return VirtualFileKotlinClass.Factory.create(file);
            }
        });

        map.put(url, new CachedResult(timeStamp, kotlinClass));

        return kotlinClass;
    }

    @Override
    public void dispose() {
        map.clear();
    }
}
