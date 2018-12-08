/*
 * Copyright 2018 Netflix, Inc.
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
 *
 */
package com.netflix.priam.configSource;

import javax.inject.Inject;

/**
 * Default {@link com.netflix.priam.configSource.IConfigSource} pulling in configs from SimpleDB,
 * local Properties, and System Properties.
 */
public class PriamConfigSource extends CompositeConfigSource {

    @Inject
    public PriamConfigSource(
            final SimpleDBConfigSource simpleDBConfigSource,
            final PropertiesConfigSource propertiesConfigSource,
            final SystemPropertiesConfigSource systemPropertiesConfigSource) {
        // As per design of the CompositeConfigSource i.e. PriamConfigSource
        // Given property give will be searched in the order as mentioned here.
        // So the property will be searched in SDB and if found will be return, else the loop
        // will continue with the next ConfigSource. i.e. propertiesConfigSource etc
        super(simpleDBConfigSource, systemPropertiesConfigSource, propertiesConfigSource);
    }
}
