import { useState } from 'react';
import { RotateCcw, Trophy, Users, Bot } from 'lucide-react';

const WIN_LINES = [
  [0, 1, 2],
  [3, 4, 5],
  [6, 7, 8],
  [0, 3, 6],
  [1, 4, 7],
  [2, 5, 8],
  [0, 4, 8],
  [2, 4, 6],
];

function getWinningLine(board: string[], symbol: string): number[] | null {
  for (const line of WIN_LINES) {
    if (board[line[0]] === symbol && board[line[1]] === symbol && board[line[2]] === symbol) {
      return line;
    }
  }
  return null;
}

function pickComputerMove(board: string[]): number {
  for (let i = 0; i < 9; i++) {
    if (!board[i]) {
      const next = [...board];
      next[i] = 'O';
      if (getWinningLine(next, 'O')) return i;
    }
  }
  for (let i = 0; i < 9; i++) {
    if (!board[i]) {
      const next = [...board];
      next[i] = 'X';
      if (getWinningLine(next, 'X')) return i;
    }
  }
  if (!board[4]) return 4;
  const corners = [0, 2, 6, 8].filter((idx) => !board[idx]);
  if (corners.length > 0) return corners[Math.floor(Math.random() * corners.length)];
  return board.findIndex((c) => !c);
}

export function TicTacToePanel() {
  const [vsComputer, setVsComputer] = useState(true);
  const [board, setBoard] = useState<string[]>(Array(9).fill(''));
  const [turn, setTurn] = useState<'X' | 'O'>('X');
  const [winLine, setWinLine] = useState<number[] | null>(null);
  const [winner, setWinner] = useState<'X' | 'O' | 'DRAW' | null>(null);
  const [scores, setScores] = useState({ x: 0, draws: 0, o: 0 });
  const [aiBusy, setAiBusy] = useState(false);

  const startNewGame = () => {
    setBoard(Array(9).fill(''));
    setTurn('X');
    setWinLine(null);
    setWinner(null);
    setAiBusy(false);
  };

  const switchMode = (computerMode: boolean) => {
    setVsComputer(computerMode);
    setScores({ x: 0, draws: 0, o: 0 });
    setBoard(Array(9).fill(''));
    setTurn('X');
    setWinLine(null);
    setWinner(null);
    setAiBusy(false);
  };

  const applyMove = (currentBoard: string[], index: number, symbol: 'X' | 'O') => {
    const next = [...currentBoard];
    next[index] = symbol;
    const foundWin = getWinningLine(next, symbol);
    if (foundWin) {
      setBoard(next);
      setWinLine(foundWin);
      setWinner(symbol);
      setScores((prev) => ({
        ...prev,
        x: symbol === 'X' ? prev.x + 1 : prev.x,
        o: symbol === 'O' ? prev.o + 1 : prev.o,
      }));
      return { ended: true, nextBoard: next };
    }
    if (next.every(Boolean)) {
      setBoard(next);
      setWinner('DRAW');
      setScores((prev) => ({ ...prev, draws: prev.draws + 1 }));
      return { ended: true, nextBoard: next };
    }
    setBoard(next);
    setTurn(symbol === 'X' ? 'O' : 'X');
    return { ended: false, nextBoard: next };
  };

  const handleCellClick = (index: number) => {
    if (winner || aiBusy || board[index]) return;
    const { ended, nextBoard } = applyMove(board, index, turn);
    if (!ended && vsComputer && turn === 'X') {
      setAiBusy(true);
      window.setTimeout(() => {
        const aiIdx = pickComputerMove(nextBoard);
        if (aiIdx >= 0) {
          applyMove(nextBoard, aiIdx, 'O');
        }
        setAiBusy(false);
      }, 220);
    }
  };

  const statusText =
    winner === 'X'
      ? 'Player X Wins!'
      : winner === 'O'
        ? vsComputer
          ? 'Computer Wins!'
          : 'Player O Wins!'
        : winner === 'DRAW'
          ? 'Match Draw!'
          : aiBusy
            ? 'Computer Thinking...'
            : turn === 'O' && vsComputer
              ? "Computer O's Turn"
              : `Player ${turn}'s Turn`;

  return (
    <div className="bg-blue-50/70 border border-blue-200 rounded-2xl p-6 max-w-xl mx-auto space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-bold text-blue-950">WCL X-O Arena (Tic Tac Toe)</h2>
          <p className="text-xs text-blue-700">Premium Blue &amp; White edition</p>
        </div>
        <div className="inline-flex bg-white border border-blue-200 rounded-xl p-1 gap-1">
          <button
            type="button"
            onClick={() => switchMode(true)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold cursor-pointer transition-colors ${
              vsComputer ? 'bg-blue-600 text-white' : 'text-blue-700 hover:bg-blue-50'
            }`}
          >
            <Bot className="w-3.5 h-3.5" />
            <span>vs Computer</span>
          </button>
          <button
            type="button"
            onClick={() => switchMode(false)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold cursor-pointer transition-colors ${
              !vsComputer ? 'bg-blue-600 text-white' : 'text-blue-700 hover:bg-blue-50'
            }`}
          >
            <Users className="w-3.5 h-3.5" />
            <span>2 Players</span>
          </button>
        </div>
      </div>

      {/* Scoreboard */}
      <div className="grid grid-cols-3 gap-3">
        <div className="bg-white border border-blue-200 rounded-xl p-3 text-center">
          <div className="text-[11px] font-bold text-blue-600">PLAYER X</div>
          <div className="text-2xl font-bold text-slate-900 tabular-nums mt-0.5">{scores.x}</div>
        </div>
        <div className="bg-white border border-blue-200 rounded-xl p-3 text-center">
          <div className="text-[11px] font-bold text-slate-500">DRAWS</div>
          <div className="text-2xl font-bold text-slate-900 tabular-nums mt-0.5">{scores.draws}</div>
        </div>
        <div className="bg-white border border-blue-200 rounded-xl p-3 text-center">
          <div className="text-[11px] font-bold text-blue-800">
            {vsComputer ? 'COMPUTER O' : 'PLAYER O'}
          </div>
          <div className="text-2xl font-bold text-slate-900 tabular-nums mt-0.5">{scores.o}</div>
        </div>
      </div>

      {/* Status Banner */}
      <div className="bg-blue-700 text-white rounded-xl py-2.5 px-4 flex items-center justify-center gap-2 text-sm font-semibold">
        <Trophy className="w-4 h-4 text-blue-200" />
        <span>{statusText}</span>
      </div>

      {/* 3x3 Board */}
      <div className="bg-white border border-blue-200 rounded-2xl p-4 grid grid-cols-3 gap-3">
        {board.map((cell, idx) => {
          const isWinningCell = winLine?.includes(idx) ?? false;
          return (
            <button
              key={idx}
              type="button"
              onClick={() => handleCellClick(idx)}
              className={`h-24 rounded-xl text-4xl font-extrabold transition-all cursor-pointer flex items-center justify-center border ${
                isWinningCell
                  ? 'bg-blue-600 text-white border-blue-700 shadow-sm'
                  : cell === 'X'
                    ? 'bg-blue-100 text-blue-700 border-blue-200'
                    : cell === 'O'
                      ? 'bg-sky-100 text-slate-900 border-sky-200'
                      : 'bg-blue-50/70 hover:bg-blue-100/80 text-blue-900 border-blue-100'
              }`}
            >
              {cell}
            </button>
          );
        })}
      </div>

      {/* Actions */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={startNewGame}
          className="flex-1 py-2.5 px-4 rounded-xl bg-blue-600 hover:bg-blue-700 text-white text-sm font-semibold cursor-pointer transition-colors"
        >
          New Game
        </button>
        <button
          type="button"
          onClick={() => switchMode(vsComputer)}
          className="inline-flex items-center gap-1.5 py-2.5 px-4 rounded-xl bg-white border border-blue-300 text-blue-700 hover:bg-blue-50 text-sm font-semibold cursor-pointer transition-colors"
        >
          <RotateCcw className="w-4 h-4" />
          <span>Reset Score</span>
        </button>
      </div>
    </div>
  );
}
