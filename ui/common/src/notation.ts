import { Move, parseUsi, Position } from 'shogiops';
import { parseSfen } from 'shogiops/sfen';
import { makeJapaneseMove } from 'shogiops/notation/japanese';
import { makeKitaoKawasakiMove } from 'shogiops/notation/kitaoKawasaki';
import { makeWesternMove } from 'shogiops/notation/western';
import { makeWesternEngineMove } from 'shogiops/notation/westernEngine';

export const enum Notation {
  Western,
  Kawasaki,
  Japanese,
  WesternEngine,
}

// Notations, that should be displayed with ☖/☗
export const notationsWithColor = [Notation.Kawasaki, Notation.Japanese];

export function makeNotationWithPosition(notation: Notation, pos: Position, move: Move, lastMove?: Move): string {
  switch (notation) {
    case Notation.Kawasaki:
      return makeKitaoKawasakiMove(pos, move, lastMove?.to)!;
    case Notation.Japanese:
      return makeJapaneseMove(pos, move, lastMove?.to)!;
    case Notation.WesternEngine:
      return makeWesternEngineMove(pos, move)!;
    default:
      return makeWesternMove(pos, move)!;
  }
}

export function makeNotationLineWithPosition(
  notation: Notation,
  pos: Position,
  moves: Move[],
  lastMove?: Move
): MoveNotation[] {
  pos = pos.clone();
  const moveLine = [];
  for (const move of moves) {
    moveLine.push(makeNotationWithPosition(notation, pos, move, lastMove));
    lastMove = move;
    pos.play(move);
  }
  return moveLine;
}

export function makeNotation(
  notation: Notation,
  sfen: Sfen,
  variant: VariantKey,
  usi: Usi,
  lastUsi?: Usi
): MoveNotation {
  const pos = createPosition(sfen, variant);
  const lastMove = lastUsi ? parseUsi(lastUsi)! : undefined;
  return makeNotationWithPosition(notation, pos, parseUsi(usi)!, lastMove);
}

export function makeNotationLine(
  notation: Notation,
  sfen: Sfen,
  variant: VariantKey,
  usis: Usi[],
  lastUsi?: Usi
): MoveNotation[] {
  return makeNotationLineWithPosition(
    notation,
    createPosition(sfen, variant),
    usis.map(u => parseUsi(u)!),
    lastUsi ? parseUsi(lastUsi) : undefined
  );
}

function createPosition(sfen: Sfen, variant: VariantKey): Position {
  return parseSfen(variant, sfen, false).unwrap();
}
