/*
 * Copyright 2017 Lukas Krejci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package org.revapi;

import static org.revapi.Revapi.TIMING_LOG;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the result of the analysis. The outputs of the analysis are generated by the reporters the Revapi instance
 * is configured with and as such are not directly accessible through this object.
 *
 * <p>To properly close the resource acquired by the extensions during the analysis, one has to {@link #close()} this
 * analysis results object.
 *
 * @author Lukas Krejci
 * @since 0.8.0
 */
public final class AnalysisResult implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisResult.class);

    private final Exception failure;
    private final Extensions extensions;

    AnalysisResult(@Nullable Exception failure, Extensions extensions) {
        this.failure = failure;
        this.extensions = extensions;
    }

    public boolean isSuccess() {
        return failure == null;
    }

    /**
     * @return the error thrown during the analysis or null if the analysis completed without failures
     */
    public @Nullable Exception getFailure() {
        return failure;
    }

    /**
     * @return the extension instances run during the analysis, each with its corresponding analysis context containing
     * the configuration used for the extension
     */
    public Extensions getExtensions() {
        return extensions;
    }

    @Override public void close() throws Exception {
        TIMING_LOG.debug("Closing all extensions");
        for (Map.Entry<?, AnalysisContext> e : extensions) {
            Object ext = e.getKey();
            if (!(ext instanceof AutoCloseable)) {
                continue;
            }

            AutoCloseable c = (AutoCloseable) ext;
            try {
                c.close();
            } catch (Exception ex) {
                LOG.warn("Failed to close " + c, ex);
            }
        }
        TIMING_LOG.debug("Extensions closed. Analysis complete.");
        TIMING_LOG.debug(Stats.asString());
    }

    public static final class Extensions implements Iterable<Map.Entry<?, AnalysisContext>> {
        private final Map<ApiAnalyzer, AnalysisContext> analyzers;
        private final Map<ElementFilter, AnalysisContext> filters;
        private final Map<Reporter, AnalysisContext> reporters;
        private final Map<DifferenceTransform<?>, AnalysisContext> transforms;

        Extensions(Map<ApiAnalyzer, AnalysisContext> analyzers, Map<ElementFilter, AnalysisContext> filters,
                          Map<Reporter, AnalysisContext> reporters,
                          Map<DifferenceTransform<?>, AnalysisContext> transforms) {
            this.analyzers = Collections.unmodifiableMap(analyzers);
            this.filters = Collections.unmodifiableMap(filters);
            this.reporters = Collections.unmodifiableMap(reporters);
            this.transforms = Collections.unmodifiableMap(transforms);
        }

        public Map<ApiAnalyzer, AnalysisContext> getAnalyzers() {
            return analyzers;
        }

        public Map<ElementFilter, AnalysisContext> getFilters() {
            return filters;
        }

        public Map<Reporter, AnalysisContext> getReporters() {
            return reporters;
        }

        public Map<DifferenceTransform<?>, AnalysisContext> getTransforms() {
            return transforms;
        }

        public <T> Map<T, AnalysisContext> getExtensions(Class<T> extensionType) {
            IdentityHashMap<T, AnalysisContext> ret = new IdentityHashMap<>();
            stream().filter(e -> extensionType.isAssignableFrom(e.getKey().getClass()))
                    .forEach(e -> ret.put(extensionType.cast(e.getKey()), e.getValue()));
            return ret;
        }

        @Override public Iterator<Map.Entry<?, AnalysisContext>> iterator() {
            return stream().iterator();
        }

        public Stream<Map.Entry<?, AnalysisContext>> stream() {
            return Stream.concat(analyzers.entrySet().stream(),
                    Stream.concat(filters.entrySet().stream(),
                            Stream.concat(reporters.entrySet().stream(),
                                    transforms.entrySet().stream())));
        }
    }
}
