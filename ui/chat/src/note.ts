import { h, VNode } from 'snabbdom';
import spinner from 'common/spinner';
import { NoteCtrl, NoteOpts } from './interfaces';
import * as xhr from './xhr';

export function noteCtrl(opts: NoteOpts): NoteCtrl {
  let text: string | undefined = opts.text;
  const doPost = window.lishogi.debounce(() => {
    xhr.setNote(opts.id, text || '');
  }, 1000);
  return {
    id: opts.id,
    trans: opts.trans,
    text: () => text,
    fetch() {
      xhr.getNote(opts.id).then(t => {
        text = t || '';
        opts.redraw();
      });
    },
    post(t) {
      text = t;
      doPost();
    },
  };
}

export function noteView(ctrl: NoteCtrl): VNode {
  const text = ctrl.text();
  if (text == undefined)
    return h(
      'div.loading',
      {
        hook: {
          insert: ctrl.fetch,
        },
      },
      [spinner()]
    );
  return h('textarea', {
    attrs: {
      placeholder: ctrl.trans('typePrivateNotesHere'),
    },
    hook: {
      insert(vnode) {
        const $el = $(vnode.elm as HTMLElement);
        $el.val(text).on('change keyup paste', () => {
          ctrl.post($el.val());
        });
      },
    },
  });
}
