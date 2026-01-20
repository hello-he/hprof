<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title} - ${packageName}</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background: #f5f7fa;
            color: #2c3e50;
            line-height: 1.6;
        }

        .header {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 2rem;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        }

        .header h1 {
            font-size: 1.8rem;
            margin-bottom: 0.5rem;
        }

        .header .meta {
            opacity: 0.9;
            font-size: 0.9rem;
        }

        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 2rem;
        }

        .card {
            background: white;
            border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.1);
            margin-bottom: 1.5rem;
            overflow: hidden;
        }

        .card-header {
            padding: 1rem 1.5rem;
            border-bottom: 1px solid #e5e7eb;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .card-header h2 {
            font-size: 1.2rem;
            font-weight: 600;
        }

        .card-body {
            padding: 1.5rem;
        }

        .summary-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 1rem;
        }

        .summary-item {
            background: #f8fafc;
            padding: 1rem;
            border-radius: 6px;
            text-align: center;
        }

        .summary-item .value {
            font-size: 2rem;
            font-weight: 700;
            color: #667eea;
        }

        .summary-item .label {
            font-size: 0.85rem;
            color: #64748b;
            margin-top: 0.25rem;
        }

        .summary-item.danger .value {
            color: #ef4444;
        }

        .summary-item.warning .value {
            color: #f59e0b;
        }

        .screenshot-container {
            display: flex;
            justify-content: center;
            padding: 1rem;
            background: #1e293b;
            border-radius: 6px;
        }

        .screenshot-container img {
            max-width: 100%;
            max-height: 400px;
            border-radius: 4px;
        }

        .leak-item {
            border-left: 4px solid #ef4444;
            padding: 1rem;
            margin-bottom: 1rem;
            background: #fef2f2;
            border-radius: 0 6px 6px 0;
        }

        .leak-item.library {
            border-left-color: #f59e0b;
            background: #fffbeb;
        }

        .leak-header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            margin-bottom: 0.75rem;
        }

        .leak-class {
            font-weight: 600;
            color: #991b1b;
            font-family: monospace;
        }

        .leak-size {
            background: #ef4444;
            color: white;
            padding: 0.25rem 0.5rem;
            border-radius: 4px;
            font-size: 0.8rem;
        }

        .leak-chain {
            font-family: monospace;
            font-size: 0.85rem;
            background: white;
            padding: 0.75rem;
            border-radius: 4px;
            overflow-x: auto;
        }

        .chain-item {
            display: block;
            padding: 0.25rem 0;
        }

        .chain-item::before {
            content: "  → ";
            color: #9ca3af;
        }

        .chain-item.gc-root::before {
            content: "★ ";
            color: #f59e0b;
        }

        .chain-item.leaked::before {
            content: "◆ ";
            color: #ef4444;
        }

        .bitmap-gallery {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
            gap: 1rem;
        }

        .bitmap-item {
            border: 1px solid #e5e7eb;
            border-radius: 6px;
            overflow: hidden;
            transition: transform 0.2s, box-shadow 0.2s;
        }

        .bitmap-item:hover {
            transform: translateY(-2px);
            box-shadow: 0 4px 12px rgba(0,0,0,0.15);
        }

        .bitmap-item.large {
            border-color: #f59e0b;
        }

        .bitmap-item.leaked {
            border-color: #ef4444;
        }

        .bitmap-item.duplicate {
            border-color: #8b5cf6;
        }

        .bitmap-preview {
            width: 100%;
            height: 120px;
            object-fit: contain;
            background: repeating-conic-gradient(#f0f0f0 0% 25%, #ffffff 0% 50%) 50% / 20px 20px;
        }

        .bitmap-preview.placeholder {
            display: flex;
            align-items: center;
            justify-content: center;
            background: #f1f5f9;
            color: #64748b;
            font-size: 0.8rem;
            padding: 0.5rem;
            text-align: center;
        }

        .bitmap-info {
            padding: 0.75rem;
            font-size: 0.8rem;
        }

        .bitmap-size {
            font-weight: 600;
            margin-bottom: 0.25rem;
        }

        .bitmap-meta {
            color: #64748b;
            font-size: 0.75rem;
        }

        .badge {
            display: inline-block;
            padding: 0.2rem 0.5rem;
            border-radius: 4px;
            font-size: 0.7rem;
            font-weight: 600;
            margin-right: 0.25rem;
        }

        .badge-danger {
            background: #fef2f2;
            color: #dc2626;
        }

        .badge-warning {
            background: #fffbeb;
            color: #d97706;
        }

        .badge-info {
            background: #eff6ff;
            color: #2563eb;
        }

        .duplicate-group {
            margin-bottom: 1.5rem;
            border: 1px solid #e5e7eb;
            border-radius: 8px;
            overflow: hidden;
        }

        .duplicate-header {
            background: #f5f3ff;
            padding: 1rem;
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid #e5e7eb;
        }

        .duplicate-bitmaps {
            padding: 1rem;
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
            gap: 0.75rem;
        }

        .empty-state {
            text-align: center;
            padding: 3rem;
            color: #64748b;
        }

        .empty-state svg {
            width: 64px;
            height: 64px;
            margin-bottom: 1rem;
            opacity: 0.5;
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>📊 Memory Leak Report</h1>
        <div class="meta">
            Package: ${packageName} | PID: ${pid} | ${timestamp}
        </div>
    </div>

    <div class="container">
        <!-- 汇总信息 -->
        <div class="card">
            <div class="card-header">
                <h2>📈 汇总</h2>
            </div>
            <div class="card-body">
                <div class="summary-grid">
                    <div class="summary-item">
                        <div class="value">${snapshot.heapUsagePercent}%</div>
                        <div class="label">堆内存使用率</div>
                    </div>
                    <div class="summary-item">
                        <div class="value">${snapshot.heapUsed} / ${snapshot.heapMax}</div>
                        <div class="label">堆内存</div>
                    </div>
                    <div class="summary-item">
                        <div class="value">${snapshot.threadCount}</div>
                        <div class="label">线程数</div>
                    </div>
                    <div class="summary-item">
                        <div class="value">${snapshot.fdCount}</div>
                        <div class="label">文件句柄</div>
                    </div>
                    <#if summary.totalLeakCount gt 0>
                    <div class="summary-item danger">
                        <div class="value">${summary.totalLeakCount}</div>
                        <div class="label">总泄漏数</div>
                    </div>
                    </#if>
                    <#if summary.totalLeakBytes gt 0>
                    <div class="summary-item danger">
                        <div class="value">${summary.totalLeakBytes}</div>
                        <div class="label">泄漏内存</div>
                    </div>
                    </#if>
                </div>
            </div>
        </div>

        <!-- 截图 -->
        <#if screenshot?? && screenshot.exists>
        <div class="card">
            <div class="card-header">
                <h2>📸 截图</h2>
            </div>
            <div class="card-body">
                <div class="screenshot-container">
                    <img src="../${screenshot.path}" alt="Screenshot">
                </div>
            </div>
        </div>
        </#if>

        <!-- 泄漏对象 -->
        <#if leakingObjects?has_content>
        <div class="card">
            <div class="card-header">
                <h2>🚨 泄漏对象 (${leakingObjects?size})</h2>
            </div>
            <div class="card-body">
                <#list leakingObjects as leak>
                <div class="leak-item">
                    <div class="leak-header">
                        <span class="leak-class">${leak.className}</span>
                        <span class="leak-size">${leak.size}</span>
                    </div>
                    <div class="leak-chain">
                        <span class="chain-item gc-root">GC Root: ${leak.gcRoot}</span>
                        <#list leak.referenceChain as ref>
                        <span class="chain-item">${ref.fullName} (${ref.referenceType})</span>
                        </#list>
                        <span class="chain-item leaked">${leak.className} ◄─ LEAKED</span>
                    </div>
                    <#if leak.instanceCount gt 1>
                    <div style="margin-top: 0.5rem; color: #64748b; font-size: 0.8rem;">
                        相同泄漏数量: ${leak.instanceCount}
                    </div>
                    </#if>
                </div>
                </#list>
            </div>
        </div>
        <#else>
        <div class="card">
            <div class="card-header">
                <h2>🚨 泄漏对象</h2>
            </div>
            <div class="card-body">
                <div class="empty-state">
                    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                    </svg>
                    <p>✅ 未发现泄漏对象</p>
                </div>
            </div>
        </div>
        </#if>

        <!-- 过大Bitmap -->
        <#if oversizedBitmaps?has_content>
        <div class="card">
            <div class="card-header">
                <h2>🖼️ 过大Bitmap (${oversizedBitmaps?size})</h2>
            </div>
            <div class="card-body">
                <div class="bitmap-gallery">
                    <#list oversizedBitmaps as bitmap>
                    <div class="bitmap-item large">
                        <#if bitmap.hasImage>
                        <img src="../bitmaps/${bitmap.pngPath}" class="bitmap-preview" alt="Bitmap">
                        <#else>
                        <div class="bitmap-preview placeholder">图片未提取</div>
                        </#if>
                        <div class="bitmap-info">
                            <div class="bitmap-size">${bitmap.sizeInfo}</div>
                            <div class="bitmap-meta">${bitmap.config}</div>
                        </div>
                    </div>
                    </#list>
                </div>
            </div>
        </div>
        </#if>

        <!-- 泄漏Bitmap -->
        <#if leakedBitmaps?has_content>
        <div class="card">
            <div class="card-header">
                <h2>🔴 泄漏Bitmap (${leakedBitmaps?size})</h2>
            </div>
            <div class="card-body">
                <div class="bitmap-gallery">
                    <#list leakedBitmaps as bitmap>
                    <div class="bitmap-item leaked">
                        <#if bitmap.hasImage>
                        <img src="../bitmaps/${bitmap.pngPath}" class="bitmap-preview" alt="Bitmap">
                        <#else>
                        <div class="bitmap-preview placeholder">图片未提取</div>
                        </#if>
                        <div class="bitmap-info">
                            <div class="bitmap-size">${bitmap.sizeInfo}</div>
                            <div class="bitmap-meta">${bitmap.config} | ${bitmap.objectId}</div>
                        </div>
                    </div>
                    </#list>
                </div>
            </div>
        </div>
        </#if>

        <!-- 重复Bitmap -->
        <#if duplicateBitmaps?has_content>
        <div class="card">
            <div class="card-header">
                <h2>🔁 重复Bitmap (${duplicateBitmaps?size}组)</h2>
            </div>
            <div class="card-body">
                <#list duplicateBitmaps as group>
                <div class="duplicate-group">
                    <div class="duplicate-header">
                        <div>
                            <strong>Hash:</strong> ${group.hash}
                            <span class="badge badge-info">${group.count} 个重复</span>
                        </div>
                        <div>
                            <span class="badge badge-warning">总计: ${group.totalBytes}</span>
                            <span class="badge badge-danger">可节省: ${group.savableBytes}</span>
                        </div>
                    </div>
                    <div class="duplicate-bitmaps">
                        <#list group.bitmaps as bitmap>
                        <div class="bitmap-item duplicate">
                            <#if bitmap.hasImage>
                            <img src="../bitmaps/${bitmap.pngPath}" class="bitmap-preview" alt="Bitmap">
                            <#else>
                            <div class="bitmap-preview placeholder">图片未提取</div>
                            </#if>
                            <div class="bitmap-info">
                                <div class="bitmap-size">${bitmap.sizeInfo}</div>
                                <div class="bitmap-meta">${bitmap.config}</div>
                            </div>
                        </div>
                        </#list>
                    </div>
                </div>
                </#list>
            </div>
        </div>
        <#else>
        <div class="card">
            <div class="card-header">
                <h2>🔁 重复Bitmap</h2>
            </div>
            <div class="card-body">
                <div class="empty-state">
                    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                    </svg>
                    <p>✅ 未发现重复Bitmap</p>
                </div>
            </div>
        </div>
        </#if>
    </div>

    <div style="text-align: center; padding: 2rem; color: #9ca3af; font-size: 0.85rem;">
        Generated by mem-monitor | Powered by KOOM Shark
    </div>
</body>
</html>
