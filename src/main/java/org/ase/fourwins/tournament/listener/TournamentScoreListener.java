package org.ase.fourwins.tournament.listener;

import org.ase.fourwins.board.Board.Score;
import org.ase.fourwins.game.Game;
import org.ase.fourwins.game.Player;
import org.ase.fourwins.tournament.ScoreSheet;

import lombok.Getter;

public class TournamentScoreListener implements TournamentListener {

	private static final double FULL_POINT = 1;
	private static final double ZERO = 0.0;
	private static final double HALF_POINT = 0.5;
	@Getter
	private final ScoreSheet scoreSheet = new ScoreSheet();

	@Override
	public void gameEnded(Game game) {
		Object lastToken = game.gameState().getToken();
		Score score = game.gameState().getScore();
		if (score.equals(Score.WIN)) {
			addPointForPlayer(game.getPlayerForToken(lastToken), FULL_POINT);
			addPointForPlayer(game.getOpponentForToken(lastToken), ZERO);
		} else if (score.equals(Score.DRAW)) {
			addPointForPlayer(game.getPlayerForToken(lastToken), HALF_POINT);
			addPointForPlayer(game.getOpponentForToken(lastToken), HALF_POINT);
		} else if (score.equals(Score.LOSE)) {
			addPointForPlayer(game.getOpponentForToken(lastToken), FULL_POINT);
			addPointForPlayer(game.getPlayerForToken(lastToken), ZERO);
		}
	}

	private void addPointForPlayer(Player player, double value) {
		scoreSheet.merge(player, value, (i, j) -> i + value);
	}

}