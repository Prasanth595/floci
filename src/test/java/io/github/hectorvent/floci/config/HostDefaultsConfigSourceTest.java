package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;

class HostDefaultsConfigSourceTest {

    @Test
    void emptyOutsideANativeHostRun() {
        HostDefaultsConfigSource source = new HostDefaultsConfigSource(false);
        assertThat(source.getProperties(), aMapWithSize(0));
        assertThat(source.getValue(HostDefaultsConfigSource.PERSISTENT_PATH), nullValue());
    }

    @Test
    void jvmConstructorIsEmpty() {
        assertThat(new HostDefaultsConfigSource().getProperties(), aMapWithSize(0));
    }

    @Test
    void nativeHostRunSetsBothPathsToTheSameDirectory() {
        HostDefaultsConfigSource source = new HostDefaultsConfigSource(true);
        String dir = source.getValue(HostDefaultsConfigSource.PERSISTENT_PATH);
        assertThat(source.getValue(HostDefaultsConfigSource.HOST_PERSISTENT_PATH), equalTo(dir));
    }

    @Test
    void ordinalOverridesOnlyTheBundledYaml() {
        // classpath application.yml is 255, config/application.yml in the working directory 265.
        assertThat(new HostDefaultsConfigSource(false).getOrdinal(), allOf(greaterThan(255), lessThan(265)));
    }
}
