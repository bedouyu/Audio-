package com.bedouyu.audiorag.tools.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechRateCalibrationTest {
    private String chinese(int characters) { return "字".repeat(characters); }

    @Test void measuredSlowerSpeechRaisesTheScaleSoMoreTextIsBudgeted() {
        var calibration = new SpeechRateCalibration();
        assertThat(calibration.scale()).isEqualTo(1.0);

        // 1950 字预计 10 分钟（600 秒），实测 720 秒 → 比例 1.2。
        calibration.learn(chinese(1950), 720);

        assertThat(calibration.samples()).isEqualTo(1);
        assertThat(calibration.scale()).isBetween(1.05, 1.25);
    }

    @Test void fasterSpeechLowersTheScale() {
        var calibration = new SpeechRateCalibration();
        calibration.learn(chinese(2600), 480);

        assertThat(calibration.scale()).isLessThan(1.0).isBetween(0.75, 0.99);
    }

    @Test void shortOrImplausibleSamplesAreIgnored() {
        var calibration = new SpeechRateCalibration();

        calibration.learn("太短", 3);                    // 预计时长过短，不可信
        calibration.learn(chinese(1950), 0.2);            // 比例远低于下限
        calibration.learn(chinese(1950), 30_000);         // 比例远高于上限
        calibration.learn(chinese(1950), Double.NaN);

        assertThat(calibration.samples()).isZero();
        assertThat(calibration.scale()).isEqualTo(1.0);
    }

    @Test void repeatedSamplesConvergeOnTheMeasuredRate() {
        var calibration = new SpeechRateCalibration();
        for (int index = 0; index < 12; index++) calibration.learn(chinese(1950), 720);

        assertThat(calibration.scale()).isCloseTo(1.2, org.assertj.core.api.Assertions.within(0.01));
        calibration.reset();
        assertThat(calibration.scale()).isEqualTo(1.0);
    }
}
