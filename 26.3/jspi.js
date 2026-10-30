/**
 * The JavaScript side of the Fast build's fibers (WebAssembly JavaScript Promise Integration; TeaVM's
 * patched `Fiber.Jspi` in tools/teavm-patches; ADR 0036). The Fast build has no coroutines:
 * a Java thread that blocks calls `teavmJspi.await`, which hands the engine a promise, and the engine
 * keeps the thread's whole WebAssembly stack until the promise settles. Each thread runs in its own
 * call of the module's `teavm.jspi.run` export through `WebAssembly.promising`, so suspending one
 * returns to whoever started it. The Compatible build imports none of this.
 *
 * The engine never frees a suspended stack, nor anything it reaches: a fiber still waiting when the
 * page goes away would keep the whole program alive in the tab's process through every reload. So
 * `terminate` (on `pagehide`) resumes each waiting fiber with an order to end, which it obeys with a
 * trap that unwinds its stack past every Java handler.
 */

/** What JSPI adds to `WebAssembly`; missing in engines without it. */
                   
                                                                
                                                                                           
 

/** The parts of the global scope the imports read, so tests can supply their own. */
                            
                                
 

export function supportsJspi(scope            = globalThis)          {
  const api = scope.WebAssembly                                ;
  return typeof api?.Suspending === "function" && typeof api.promising === "function";
}

                              
                                       
                                           
                                                                                    
                                                 
                                                                                    
                    
                                                                            
                    
 

/** What a fiber's wait resumes with: carry on, or end (`Fiber.Jspi.suspend`). */
const RESUME = 0;
const END = 1;

/**
 * Builds the `teavmJspi` imports. `fail` gets an error that escapes a fiber: it can't reach the
 * starter's stack, which has moved on by the time the fiber's promise settles.
 */
export function createJspiImports(fail                          , scope            = globalThis)              {
  const api = scope.WebAssembly           ;
  const promises = new Map                                                                        ();
  let nextId = 1;
  let run                                            = null;
  let ending = false;
  // The fibers' traps on the way out aren't failures.
  const report = (error         )       => {
    if (!ending) {
      fail(error);
    }
  };

  const module                          = {
    create()         {
      const id = nextId++;
      let resolve                          ;
      const promise = new Promise        ((r) => {
        resolve = r;
      });
      promises.set(id, { promise, resolve });
      return id;
    },
    // Hands the engine the promise: the calling fiber's stack waits for it.
    await: new api.Suspending((id        )                  => {
      const entry = promises.get(id);
      if (entry === undefined) {
        throw new Error(`No JSPI promise ${String(id)} to wait for`);
      }
      if (ending) {
        promises.delete(id);
        return Promise.resolve(END);
      }
      return entry.promise;
    }),
    resolve(id        )       {
      const entry = promises.get(id);
      promises.delete(id);
      entry?.resolve(ending ? END : RESUME);
    },
    // A call that completed before its fiber had to wait.
    drop(id        )       {
      promises.delete(id);
    },
    run(id        )       {
      if (run === null) {
        throw new Error("A fiber started before the module's exports were attached");
      }
      // Returns once the fiber ends or first waits; the rest runs as its promises settle.
      run(id).catch(report);
    },
  };

  return {
    module,
    attach(exports) {
      const runExport = exports["teavm.jspi.run"];
      if (typeof runExport === "function") {
        run = api.promising(runExport                           )                                    ;
      }
    },
    pending: () => promises.size,
    terminate() {
      ending = true;
      for (const entry of promises.values()) {
        entry.resolve(END);
      }
      promises.clear();
    },
  };
}
