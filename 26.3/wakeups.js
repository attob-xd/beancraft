/**
 * TeaVM's event queue in the browser (`teavmAsync`), without the browser's timer clamp. TeaVM hands
 * every event to `setTimeout`, a woken thread too (a `notify`, a finished wait): due at once, so a
 * zero delay. But browsers hold a zero-delay timer set from inside a timer's callback, five deep, to
 * at least 4 ms, and one thread waking another is just such a chain. In singleplayer's server worker
 * each chunk storage scan (the game's structure check) is a hand-off of that kind between its IO
 * thread and the world generation waiting on it, so a new world's ~8,000 scans took 16-22 seconds
 * of timers, with the worker idle.
 *
 * Here an event due now goes on a queue that a `MessageChannel` drains, one event per message: the
 * order TeaVM gave them, and no clamp (the background pool's slots use the same, `WorkerSlots`). An
 * event due later still waits on `setTimeout`. Ids for queued events are negative, so `kill` knows
 * which kind it cancels.
 */

/** The `teavmAsync` import module: `offer` returns an id `kill` takes back. */
                               
                                                                                  
                         
 

/** What the imports need from the global scope, so tests can supply their own. */
                              
                                           
                                                         
                                 
                
 

const browserScope              = {
  MessageChannel,
  setTimeout: (handler, delay) => setTimeout(handler, delay)                     ,
  clearTimeout: (id) => {
    clearTimeout(id);
  },
  now: () => Date.now(),
};

export function createAsyncImports(scope              = browserScope)               {
  const channel = new scope.MessageChannel();
  // Insertion order is the order events were offered in.
  const due = new Map                    ();
  let nextId = 1;
  channel.port1.onmessage = () => {
    const first = due.entries().next();
    if (first.done !== true) {
      const [id, run] = first.value;
      due.delete(id);
      run();
    }
  };
  return {
    offer(instance, fn, time) {
      const delay = time - scope.now();
      if (delay > 0) {
        return scope.setTimeout(() => {
          fn(instance);
        }, delay);
      }
      const id = -nextId++;
      due.set(id, () => {
        fn(instance);
      });
      channel.port2.postMessage(0);
      return id;
    },
    kill(id) {
      if (id < 0) {
        // Its message still comes; it runs the next event due, or nothing.
        due.delete(id);
      } else {
        scope.clearTimeout(id);
      }
    },
  };
}
