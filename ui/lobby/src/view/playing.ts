import { h } from 'snabbdom';
import { Shogiground } from 'shogiground';
import LobbyController from '../ctrl';
import { handRoles } from 'shogiops/variantUtil';
import { usiToSquareNames } from 'shogiops/compat';

function timer(pov) {
  const date = Date.now() + pov.secondsLeft * 1000;
  return h(
    'time.timeago',
    {
      hook: {
        insert(vnode) {
          (vnode.elm as HTMLElement).setAttribute('datetime', '' + date);
        },
      },
    },
    window.lishogi.timeago.format(date)
  );
}

export default function (ctrl: LobbyController) {
  return h(
    'div.now-playing',
    ctrl.data.nowPlaying.map(function (pov) {
      return h(
        'a.' + pov.variant.key + (pov.isMyTurn ? '.my_turn' : ''),
        {
          key: pov.gameId,
          attrs: { href: '/' + pov.fullId },
        },
        [
          h('div.mini-board.sg-wrap', {
            hook: {
              insert(vnode) {
                const lm = pov.lastMove,
                  splitSfen = pov.sfen.split(' ');
                Shogiground(
                  {
                    coordinates: { enabled: false },
                    drawable: { enabled: false, visible: false },
                    viewOnly: true,
                    orientation: pov.color,
                    disableContextMenu: false,
                    sfen: {
                      board: splitSfen[0],
                      hands: splitSfen[2],
                    },
                    hands: {
                      inlined: true,
                      roles: handRoles(pov.variant.key),
                    },
                    lastDests: lm ? (usiToSquareNames(lm) as Key[]) : undefined,
                  },
                  { board: vnode.elm as HTMLElement }
                );
              },
            },
          }),
          h('span.meta', [
            pov.opponent.ai ? ctrl.trans('aiNameLevelAiLevel', 'Engine', pov.opponent.ai) : pov.opponent.username,
            h(
              'span.indicator',
              pov.isMyTurn
                ? pov.secondsLeft && pov.hasMoved
                  ? timer(pov)
                  : [ctrl.trans.noarg('yourTurn')]
                : h('span', '\xa0')
            ), // &nbsp;
          ]),
        ]
      );
    })
  );
}
