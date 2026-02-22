package com.example.devmcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildLogHolderTest {

    @Test
    void defaultMessage_isHelpful() {
        assertThat(new BuildLogHolder().getLastBuildLog())
            .isEqualTo("No build has been run yet.");
    }

    @Test
    void setAndGet_roundTrips() {
        BuildLogHolder holder = new BuildLogHolder();
        holder.setLastBuildLog("BUILD SUCCESS");
        assertThat(holder.getLastBuildLog()).isEqualTo("BUILD SUCCESS");
    }
}
