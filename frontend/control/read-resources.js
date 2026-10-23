// 每項讀取完成就交付結果，避免慢速資源拖住已完成的狀態與租約。
// 保留每項讀取的真正開始時間；收到結果不會重設其新鮮度起點。
export function readResources(tasks, now, onResult) {
  return Promise.allSettled(Object.entries(tasks).map(async ([key, read]) => {
    const started = now();
    const result = await Promise.resolve().then(read).then(
      (value) => ({ status: 'fulfilled', value }),
      (reason) => ({ status: 'rejected', reason }),
    );
    onResult(key, result, started, now());
  }));
}
