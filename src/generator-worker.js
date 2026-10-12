/**
 * 无猜雷位生成的工作线程入口（Node worker_threads）。
 * 主进程通过它执行耗时的重生成循环，避免阻塞主进程与渲染进程的事件循环。
 * 参数经 workerData 传入，结果经 parentPort 回传（结构化克隆支持 Uint8Array）。
 */
const { parentPort, workerData } = require('worker_threads')
const Generator = require('./generator.js')

try {
  const plan = Generator.generate(
    workerData.rows,
    workerData.cols,
    workerData.mines,
    workerData.safeRow,
    workerData.safeCol,
    workerData.noGuess !== false
  )
  parentPort.postMessage({ ok: true, mines: plan.mines, noGuessFallback: plan.noGuessFallback })
} catch (err) {
  parentPort.postMessage({ ok: false, error: String((err && err.message) || err) })
}
