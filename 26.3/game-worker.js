/**
 * A copy of the game's program in a module worker (ADR 0039): singleplayer's server, apart from the
 * page's thread. The page (platform-web's `JsChannel`) starts it with the build it loaded itself;
 * this loads TeaVM's runtime and that module, with the Fast build's JSPI imports (jspi.ts), and
 * starts the program in the role it was given.
 *
 * The page and the program here talk through `__incariParent`, the same four calls the page's side
 * offers: `send` a numbered message of bytes (the buffer is handed over), `take` the next one if any,
 * `next` for a promise of it, `close`. A message only queues here: Java waits for it on a thread of
 * its own. The worker's console lines show in the page's DevTools (and the browser checks) as they
 * are; a start that fails is reported to the page, which prints it.
 */
import { createJspiImports } from "./jspi.js";
import { createAsyncImports } from "./wakeups.js";

/** A numbered message of bytes; kind -1 ends the channel. */
                           
               
                    
 

/** What the page sends first, with a port of its storage worker's if it has one (ADR 0041). */
                               
                                                                                          
                               
 

/** What the program here reaches the page through. */
                                
                                              
                          
                                   
                
 

                        
           
         
                  
                                                                               
                                                                                            
    
 

/** The parts of a worker's scope this uses, so tests can supply their own. */
                              
                                                                 
                                                         
                
                   
                       
                                 
                                                                                                 
                                       
                                                                                                  
                                                               
                                                                                                 
                                              
 

/** A worker of this one's, as `startChild` uses it. */
                              
                                                                 
                                                                                       
                    
 

/** What a worker of this one's is made with, so tests can supply their own. */
                                                                              

/** A real worker, for `startChild`. */
export function browserWorker(url     , options               )              {
  const worker = new Worker(url, options);
  return {
    postMessage: (message, transfer) => {
      worker.postMessage(message, transfer ?? []);
    },
    listen(onMessage, onError) {
      worker.onmessage = (event) => {
        onMessage(event.data);
      };
      worker.onerror = (event) => {
        onError(event.message);
      };
    },
    terminate: () => {
      worker.terminate();
    },
  };
}

/**
 * Starts the program again in a worker of this one's, with the build this one runs and in `role`
 * (ADR 0042: singleplayer's server starts its terrain helpers so), and returns the channel to it:
 * the same four calls the page has to this worker.
 */
export function startChild(
  scope                              ,
  build                       ,
  role        ,
  createWorker               ,
  scriptUrl     ,
)                {
  const inbox = createInbox();
  const worker = createWorker(scriptUrl, { type: "module", name: `incari-${role}` });
  worker.listen(
    (message) => {
      const data = message                     ;
      if (data.kind === -1) {
        inbox.push(null);
      } else if (typeof data.kind === "number" && data.data instanceof ArrayBuffer) {
        inbox.push({ kind: data.kind, data: data.data });
      }
    },
    (error) => {
      scope.console.error(`[Incari] The ${role} worker failed: ${error}`);
      inbox.push(null);
    },
  );
  worker.postMessage({ start: { ...build, role }, storage: null });
  return {
    send(kind, data) {
      worker.postMessage({ kind, data }, [data]);
    },
    take: () => inbox.take(),
    next: () => inbox.next(),
    close() {
      worker.postMessage({ kind: -1, data: new ArrayBuffer(0) });
      // It ends itself on that; this only makes sure.
      setTimeout(() => {
        worker.terminate();
      }, 5000);
      inbox.push(null);
    },
  };
}

/** A queue of envelopes that a waiting reader is handed as they come. */
export function createInbox()   
                                        
                          
                                   
  {
  const queue                      = [];
  let waiting                                               = null;
  let ended = false;
  return {
    push(envelope) {
      if (ended) {
        return;
      }
      if (envelope === null) {
        ended = true;
      }
      if (waiting !== null) {
        const resolve = waiting;
        waiting = null;
        resolve(envelope);
      } else {
        queue.push(envelope);
      }
    },
    take() {
      const head = queue[0];
      if (head === undefined || head === null) {
        return null;
      }
      queue.shift();
      return head;
    },
    next() {
      if (queue.length > 0) {
        return Promise.resolve(queue.shift() ?? null);
      }
      return new Promise((resolve) => {
        waiting = resolve;
      });
    },
  };
}

/** Starts serving the page's messages on `scope`. */
export function serve(scope             )       {
  const inbox = createInbox();
  // Ends the program's waiting fibers before the worker goes, as the page does on `pagehide`
  // (jspi.ts): a worker closed with suspended stacks crashed the tab's process later (ADR 0042).
  let unwind = ()       => undefined;
  let role = "";

  scope.__incariParent = {
    send(kind, data) {
      scope.postMessage({ kind, data }, [data]);
    },
    take: () => inbox.take(),
    next: () => inbox.next(),
    close() {
      unwind();
      scope.postMessage({ kind: -1, data: new ArrayBuffer(0) });
      scope.close();
    },
  };

  async function start(message                       )                {
    // An import fetches without `fetch`, so the single-file page's shim can't answer it.
    await import(scope.incariFiles?.url(message.runtime) ?? message.runtime);
    const runtime = scope.TeaVM;
    if (runtime === undefined) {
      throw new Error(`${message.runtime} didn't define TeaVM's loader`);
    }
    const jspi = message.jspi
      ? createJspiImports((error) => {
          scope.console.error("[Incari] Worker:", error);
        })
      : null;
    const teavm = await runtime.wasmGC.load(message.module, {
      installImports: (imports) => {
        if (jspi !== null) {
          imports.teavmJspi = jspi.module;
        }
        // Threads woken without the browser's 4 ms timer clamp (wakeups.ts).
        imports.teavmAsync = createAsyncImports();
      },
    });
    jspi?.attach(teavm.instance.exports);
    unwind = () => {
      jspi?.terminate();
    };
    scope.__incariStartWorker = (role) =>
      typeof Worker === "undefined"
        ? null
        : startChild(scope, message, role, browserWorker, new URL("game-worker.js", import.meta.url));
    // main() returns at the program's first wait; the rest runs from this worker's event loop.
    teavm.exports.main(["--worker", message.role, message.query ?? ""]);
  }

  scope.onmessage = (event) => {
    const data = event.data                                             ;
    if (data.start !== undefined) {
      scope.__incariStorage = data.storage ?? null;
      role = data.start.role;
      start(data.start).catch((error         ) => {
        scope.console.error("[Incari] Worker: couldn't start the program:", error);
        scope.postMessage({ kind: -1, data: new ArrayBuffer(0) });
      });
    } else if (data.kind === -1) {
      inbox.push(null);
      // A terrain helper has nothing to hand back: it ends with its server's channel.
      if (role === "terrain") {
        unwind();
        scope.close();
      }
    } else if (typeof data.kind === "number" && data.data instanceof ArrayBuffer) {
      inbox.push({ kind: data.kind, data: data.data });
    }
  };
}

if (typeof (globalThis                                   ).WorkerGlobalScope !== "undefined") {
  serve(globalThis                          );
}
