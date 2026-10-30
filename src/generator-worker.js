/**
 * 无猜雷位生成的工作线程入口（Node worker_threads）。
 * 主进程通过它执行耗时的重生成循环，避免阻塞主进程与渲染进程的事件循环。
 * 参数经 workerData 传入，结果经 parentPort 回传（结构化克隆支持 Uint8Array）。
 *
 * M3：除 mines/noGuessFallback 外回传 rows/cols/safeRow/safeCol 几何信息，
 * 供渲染进程 applyPlan 做一致性校验；H2：支持 workerData.budgetMs/maxAttempts 覆盖预算。
 */
const { parentPort, workerData } = require('worker_threads')
const Generator = require('./generator.js')

try {
  const options = {}
  if (Number.isFinite(workerData.budgetMs) && workerData.budgetMs > 0) options.budgetMs = workerData.budgetMs
  if (Number.isInteger(workerData.maxAttempts) && workerData.maxAttempts > 0) {
    options.maxAttempts = workerData.maxAttempts
  }
  const plan = Generator.generate(
    workerData.rows,
    workerData.cols,
    workerData.mines,
    workerData.safeRow,
    workerData.safeCol,
    workerData.noGuess !== false,
    Date.now,
    Math.random,
    options
  )
  parentPort.postMessage({
    ok: true,
    mines: plan.mines,
    noGuessFallback: plan.noGuessFallback,
    rows: plan.rows,
    cols: plan.cols,
    safeRow: plan.safeRow,
    safeCol: plan.safeCol
  })
} catch (err) {
  parentPort.postMessage({ ok: false, error: String((err && err.message) || err) })
}
