package com.baseball.score.config;

/**
 * 靜態資源版本號：用於在 &lt;script&gt;/&lt;link&gt; 網址後面加上 ?v=... 做 cache busting
 * （見各 template 的 th:src="@{/js/app.js(v=${T(com.baseball.score.config.AssetVersion).VALUE})}"）。
 *
 * VALUE 是這個 class 第一次被載入時（也就是 App 啟動時）的時間戳記，之後在同一個 JVM 進程裡都不會變。
 * 每次重新部署，App 一定會重啟，VALUE 就會換成新的時間戳記，瀏覽器看到 app.js/app.css 的網址變了，
 * 就一定會重新抓最新檔案，不會被瀏覽器快取、反向代理或 CDN 快取卡住舊版本 —— 這就是先前
 * 「local 端沒問題、prod 卻抓到舊版 app.js」這類問題的根本解法：以後只要重新部署，網址就自動換新，
 * 不需要手動記得加版本號或清快取。
 */
public final class AssetVersion {
    public static final long VALUE = System.currentTimeMillis();

    private AssetVersion() {
    }
}
