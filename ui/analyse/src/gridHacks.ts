import * as gridHacks from 'common/gridHacks';

let booted = false;

export function start(container: HTMLElement) {
  const runHacks = () => fixChatHeight(container);

  gridHacks.runner(runHacks);

  gridHacks.bindShogigroundResizeOnce(runHacks);

  if (!booted) {
    window.lishogi.pubsub.on('chat.resize', runHacks);
    booted = true;
  }
}

function fixChatHeight(container: HTMLElement) {
  const chat = container.querySelector('.mchat') as HTMLElement,
    board = container.querySelector('.analyse__board .sg-wrap') as HTMLElement,
    side = container.querySelector('.analyse__side') as HTMLElement;
  if (chat && board && side) {
    const height = board.offsetHeight - side.offsetHeight;
    if (height) chat.style.height = `calc(${height}px - 4vmin)`;
  }
}
