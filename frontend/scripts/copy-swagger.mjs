// 把 Swagger UI 的静态资源从 node_modules 拷进构建产物。
//
// 不走 CDN：这是局域网自用的部署，实测拉一次 CDN 要六秒，文档页在浏览器里
// 直接超时。也不把文件提交进仓库：那是一份 1.4MB 的压缩 JS，代码扫描工具会
// 在里面报出十几条误报，不值得为它开豁免。挂在 npm run build 后面，Docker
// 构建前端时就自然带上了,运行镜像里也就有了。

import { cpSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const src = join(here, '..', 'node_modules', 'swagger-ui-dist')
const dst = join(here, '..', 'dist', 'swagger')

if (!existsSync(src)) {
  console.error('找不到 swagger-ui-dist，先跑一次 npm install')
  process.exit(1)
}

mkdirSync(dst, { recursive: true })
for (const file of ['swagger-ui-bundle.js', 'swagger-ui.css']) {
  cpSync(join(src, file), join(dst, file))
}
console.log(`swagger 静态资源已拷入 ${dst}`)
