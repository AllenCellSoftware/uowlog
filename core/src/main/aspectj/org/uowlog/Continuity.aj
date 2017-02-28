package org.uowlog;

import akka.actor.ActorCell;
import akka.actor.ActorRef;
import akka.actor.ActorRefWithCell;
import akka.actor.DeadLetter;
import akka.dispatch.DefaultSystemMessageQueue;
import akka.dispatch.Envelope;
import akka.dispatch.sysmsg.SystemMessage;
import akka.remote.RemoteActorRef;
import scala.Function0;
import scala.Function1;
import scala.Option;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Future$;

public aspect Continuity {

  // Handle Future$ apply calls, for spawning things in a Future.  No longer needed in scala 2.12.
  Future around(Function0<?> func): execution(Future Future$.apply(*,*)) && args(func, *) {
    if (UnitOfWork.current() == null || (func instanceof UOWFutureApplyWrapper))
      return proceed(func);
    else
      return proceed(new UOWFutureApplyWrapper(func));
  }

  // Handle Future$ apply calls, for spawning things in a Future.  No longer needed in scala 2.12.
  Future around(Function0<?> func): execution(Future scala.concurrent.impl.Future$.apply(*,*)) && args(func, *) {
    if (UnitOfWork.current() == null || (func instanceof UOWFutureApplyWrapper))
      return proceed(func);
    else
      return proceed(new UOWFutureApplyWrapper(func));
  }

  // Handle Future$ apply calls, for spawning things in a Future.  No longer needed in scala 2.12.
  Future around(Function0<?> func): execution(Future<?> Future$.apply(*,*)) && args(func, *) {
    if (UnitOfWork.current() == null || (func instanceof UOWFutureApplyWrapper))
      return proceed(func);
    else
      return proceed(new UOWFutureApplyWrapper(func));
  }

  // Handle Future$ apply calls, for spawning things in a Future.  No longer needed in scala 2.12.
  Future around(Function0<?> func): execution(Future<?> scala.concurrent.impl.Future$.apply(*,*)) && args(func, *) {
    if (UnitOfWork.current() == null || (func instanceof UOWFutureApplyWrapper))
      return proceed(func);
    else
      return proceed(new UOWFutureApplyWrapper(func));
  }

  // Handle Future onComplete calls (actually implemented in scala.concurrent.impl.Promise$DefaultPromise)
  // All Future maps, foreach, etc. eventually boil down to an onComplete.
  void around(Function1<?,?> func): execution(void Future.onComplete(*,*)) && args(func, *) {
    if (UnitOfWork.current() == null || (func instanceof UOWFutureOnCompleteWrapper))
      proceed(func);
    else
      proceed(new UOWFutureOnCompleteWrapper(func));
  }

  // Handle Akka actor tells.
  // THIS IS FRAGILE.  ActorRefWithCell AND RemoteActorRef ARE INTERNAL AKKA INTERFACES THAT COULD CHANGE AT ANY TIME.
  void around(final ActorRef dest, Object msg): execution(void $bang(*,*)) && target(ActorRef) && target(dest) && args(msg, *) {
//System.out.println("Sending to " + dest + "(" + dest.getClass() + ") with message " + msg);
    if (dest instanceof ActorRefWithCell) {
      if (msg instanceof UOWActorMessageWrapper || UnitOfWork.current() == null) proceed(dest, msg);
      else {
//System.out.println("... wrapping for local");
        proceed(dest, new UOWLocalActorMessageWrapper(msg));
      }
    } else if (dest instanceof RemoteActorRef) {
      if (msg instanceof UOWRemoteActorMessageWrapper || UnitOfWork.current() == null) proceed(dest, msg);
      else if (msg instanceof UOWLocalActorMessageWrapper) {
//System.out.println("... unwrapping local and rewrapping remote");
        // Crap.  It was wrapped for local delivery, but now we need to unwrap and re-wrap for remote delivery.
        final UOWLocalActorMessageWrapper local = (UOWLocalActorMessageWrapper)msg;
        local.uow().measure(new UncheckedWork() {
          public void doWork() {
            local.uow().decrementPending(local.token(), null);
            proceed(dest, new UOWRemoteActorMessageWrapper(local.contents()));
          }
        });
      } else {
//System.out.println("... wrapping for remote");
        proceed(dest, new UOWRemoteActorMessageWrapper(msg));
      }
    } else if (msg instanceof UOWLocalActorMessageWrapper) {
      // Sigh, need to unwrap for a specialized ActorRef type
//System.out.println("... unwrapping local");
      final UOWLocalActorMessageWrapper local = (UOWLocalActorMessageWrapper)msg;
      local.uow().measure(new UncheckedWork() {
        public void doWork() {
          local.uow().decrementPending(local.token(), null);
          proceed(dest, local.contents());
        }
      });
    } else if (msg instanceof UOWRemoteActorMessageWrapper) {
      // Sigh, need to unwrap for a specialized ActorRef type
//System.out.println("... unwrapping remote");
      final UOWRemoteActorMessageWrapper remote = (UOWRemoteActorMessageWrapper)msg;
      UnitOfWork uow = new UnitOfWork(UnitOfWork$.MODULE$.log(), remote.contents().getClass().getSimpleName(), null, remote.provenance(), remote.level(), UOWResult.NORMAL, UOWResult.EXCEPTION);
      uow.measure(new UncheckedWork() {
        public void doWork() {
          proceed(dest, remote.contents());
        }
      });
    } else proceed(dest, msg);
  }

  // If a message becomes a DeadLetter, then unwrap it.
  // This has to be around call instead of around execution,
  // because around execution doesn't actually modify the args properly when proceeding.
  DeadLetter around(Object msg): call(DeadLetter.new(*, *, *)) && args(msg, *, *) {
    if (msg instanceof UOWLocalActorMessageWrapper) {
      final UOWLocalActorMessageWrapper local = (UOWLocalActorMessageWrapper)msg;
      return local.uow().measure(new UncheckedWorkWithResult<DeadLetter>() {
        public DeadLetter doWork() {
          local.uow().decrementPending(local.token(), null);
          return proceed(local.contents());
        }
      });
    } else if (msg instanceof UOWRemoteActorMessageWrapper) {
      final UOWRemoteActorMessageWrapper remote = (UOWRemoteActorMessageWrapper)msg;
      UnitOfWork uow = new UnitOfWork(UnitOfWork$.MODULE$.log(), remote.contents().getClass().getSimpleName(), null, remote.provenance(), remote.level(), UOWResult.NORMAL, UOWResult.EXCEPTION);
      return uow.measure(new UncheckedWorkWithResult<DeadLetter>() {
        public DeadLetter doWork() {
          return proceed(remote.contents());
        }
      });
    } else return proceed(msg);
  }

  // This wraps invoke instead of aroundReceive so we unwrap AutoReceivedMessages too.
  // THIS IS FRAGILE.  ActorCell.invoke IS AN INTERNAL AKKA INTERFACE THAT COULD CHANGE AT ANY TIME.
  void around(final Envelope env): execution(void ActorCell.invoke(Envelope)) && args(env) {
    if (env.message() instanceof UOWLocalActorMessageWrapper) {
      final UOWLocalActorMessageWrapper msg = (UOWLocalActorMessageWrapper)env.message();
      msg.uow().measure(new UncheckedWork() {
        public void doWork() {
          msg.uow().decrementPending(msg.token(), null);
          proceed(new Envelope(msg.contents(), env.sender()));
        }
      });
    } else if (env.message() instanceof UOWRemoteActorMessageWrapper) {
      final UOWRemoteActorMessageWrapper msg = (UOWRemoteActorMessageWrapper)env.message();
      UnitOfWork uow = new UnitOfWork(UnitOfWork$.MODULE$.log(), msg.contents().getClass().getSimpleName(), null, msg.provenance(), msg.level(), UOWResult.NORMAL, UOWResult.EXCEPTION);
      uow.measure(new UncheckedWork() {
        public void doWork() {
          proceed(new Envelope(msg.contents(), env.sender()));
        }
      });
    } else proceed(env);
  }

  private UnitOfWork SystemMessage.uow = null;
  private int SystemMessage.pendingToken = 0;

  // This wraps systemInvoke to transfer provenance across lifecycle events.
  // THIS IS FRAGILE.  ActorCell.systemInvoke IS AN INTERNAL AKKA INTERFACE THAT COULD CHANGE AT ANY TIME.
  void around(final SystemMessage msg): execution(void ActorCell.systemInvoke(SystemMessage)) && args(msg) {
    if (msg.uow != null) {
      msg.uow.measure(new UncheckedWork() {
        public void doWork() {
          msg.uow.decrementPending(msg.pendingToken, null);
          proceed(msg);
        }
      });
    } else proceed(msg);
  }

  void around(final SystemMessage msg): execution(void DefaultSystemMessageQueue.systemEnqueue(ActorRef, SystemMessage)) && args(*, msg) {
    if (msg.uow != UnitOfWork.current()) {
      if (msg.uow != null) {
        Option<String> from = msg.uow.decrementPending(msg.pendingToken, null);
        if (from.isDefined()) UnitOfWork.complainRepurpose(msg.uow, from.get());
      }
      msg.uow = UnitOfWork.current();
      if (msg.uow != null) {
        msg.pendingToken = msg.uow.incrementPending();
      }
    }
    proceed(msg);
  }

  public void SystemMessage.finalize() {
    if (uow != null) {
      Option<String> from = uow.decrementPending(pendingToken, null);
      if (from.isDefined()) UnitOfWork.complainGC(uow, from.get());
    }
  }
}
