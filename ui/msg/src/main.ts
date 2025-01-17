import view from './view/main';

import { init, VNode, classModule, attributesModule } from 'snabbdom';

import { MsgOpts } from './interfaces';
import { upgradeData } from './network';
import MsgCtrl from './ctrl';

const patch = init([classModule, attributesModule]);

export default function LishogiMsg(element: HTMLElement, opts: MsgOpts) {
  const appHeight = () => document.body.style.setProperty('--app-height', `${window.innerHeight}px`);
  window.addEventListener('resize', appHeight);
  appHeight();

  let vnode: VNode, ctrl: MsgCtrl;

  function redraw() {
    vnode = patch(vnode, view(ctrl));
  }

  ctrl = new MsgCtrl(upgradeData(opts.data), window.lishogi.trans(opts.i18n), redraw);

  const blueprint = view(ctrl);
  element.innerHTML = '';
  vnode = patch(element, blueprint);

  redraw();
}

LishogiMsg.default = LishogiMsg; // TODO: remove bc after next deploy
