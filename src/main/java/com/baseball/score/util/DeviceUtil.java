package com.baseball.score.util;

import com.baseball.score.enums.DeviceType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/** 依 User-Agent 判斷 PC / MOBILE；可用 ?device=pc|mobile 手動覆寫。 */
public final class DeviceUtil {

    private static final Pattern MOBILE_UA = Pattern.compile(
            "(?i).*(android|iphone|ipod|iemobile|blackberry|windows phone|webos|opera mini|mobile safari|silk).*");

    private static final Pattern TABLET_UA = Pattern.compile("(?i).*(ipad|tablet|kindle|playbook).*");

    private DeviceUtil() {}

    public static DeviceType detect(HttpServletRequest request) {
        String override = request.getParameter("device");
        if (StringUtils.hasText(override)) {
            return "mobile".equalsIgnoreCase(override) ? DeviceType.MOBILE : DeviceType.PC;
        }
        String ua = request.getHeader("User-Agent");
        if (!StringUtils.hasText(ua)) return DeviceType.PC;
        // 平板畫面夠大，視為 PC 版面
        if (TABLET_UA.matcher(ua).matches()) return DeviceType.PC;
        return MOBILE_UA.matcher(ua).matches() ? DeviceType.MOBILE : DeviceType.PC;
    }

    public static String clientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xf)) return xf.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
