import { h } from 'snabbdom';
import { MaybeVNode } from 'common/snabbdom';
import RoundController from '../ctrl';
import { playable, isPlayerTurn } from 'game';

let rang = false;

export default function (ctrl: RoundController): MaybeVNode {
  const d = playable(ctrl.data) && ctrl.data.expiration;
  if (!d) return;
  const timeLeft = Math.max(0, d.movedAt - Date.now() + d.millisToMove),
    secondsLeft = Math.floor(timeLeft / 1000),
    myTurn = isPlayerTurn(ctrl.data),
    emerg = myTurn && timeLeft < 8000;
  if (!rang && emerg) {
    window.lishogi.sound.lowtime();
    rang = true;
  }
  const side = myTurn != ctrl.flip ? 'bottom' : 'top';
  return h(
    'div.expiration.expiration-' + side,
    {
      class: {
        emerg,
        'bar-glider': myTurn,
      },
    },
    ctrl.trans.vdomPlural('nbSecondsToPlayTheFirstMove', secondsLeft, h('strong', '' + secondsLeft))
  );
}
