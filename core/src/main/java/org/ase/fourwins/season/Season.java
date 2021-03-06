package org.ase.fourwins.season;

import static java.util.stream.Stream.concat;

import java.util.List;
import java.util.stream.Stream;

import lombok.Getter;

public class Season<T> {

	private static final class ReversedMatchday<T> extends Matchday<T> {

		private ReversedMatchday(List<T> teams) {
			super(teams);
		}

		@Override
		protected Match<T> makeMatch(int i) {
			Match<T> pair = super.makeMatch(i);
			return new Match<T>(pair.getTeam2(), pair.getTeam1());
		}
	}

	@Getter
	private final Round<T> firstRound, secondRound;

	public Season(List<T> teams) {
		this.firstRound = new Round<T>(teams);
		this.secondRound = new Round<T>(teams) {
			@Override
			protected Matchday<T> matchday(List<T> teams) {
				return new ReversedMatchday<T>(teams);
			}
		};
	}

	public Stream<Matchday<T>> getMatchdays() {
		return concat(firstRound.getMatchdays(), secondRound.getMatchdays());
	}

}