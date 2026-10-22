package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.common.SpeechTiming;
import org.springframework.stereotype.Component;

/**
 * 用真实合成结果校准语速。
 *
 * <p>中英常数只是先验：不同语音提供方（Windows SAPI、CosyVoice）与音色读同一段文字的实际秒数
 * 能差出 20% 以上。每次合成完成后把「实测秒数 / 预计秒数」按指数加权记下来，
 * 后续分段与朗读预算按该比例缩放，标称时长才会越来越准。
 */
@Component
public class SpeechRateCalibration {
    private static final double ALPHA = 0.3;
    private static final double MIN_PREDICTED_SECONDS = 5.0;
    private static final double MIN_RATIO = 0.4;
    private static final double MAX_RATIO = 2.5;

    private volatile double scale = 1.0;
    private volatile int samples;

    /** 实测比预计更慢时返回大于 1，表示同样的分钟数需要更多文字。 */
    public double scale() { return scale; }

    public int samples() { return samples; }

    /** 只吸收可信样本：太短、或超出合理区间（例如合成失败留下的残片）不参与校准。 */
    public synchronized void learn(String text, double actualSeconds) {
        double predicted = SpeechTiming.estimatedSeconds(text);
        if (predicted < MIN_PREDICTED_SECONDS || !Double.isFinite(actualSeconds) || actualSeconds < 1) return;
        double ratio = actualSeconds / predicted;
        if (!Double.isFinite(ratio) || ratio < MIN_RATIO || ratio > MAX_RATIO) return;
        scale = scale * (1 - ALPHA) + ratio * ALPHA;
        samples++;
    }

    public synchronized void reset() {
        scale = 1.0;
        samples = 0;
    }
}
