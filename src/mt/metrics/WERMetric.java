package mt.metrics;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.List;

import edu.stanford.nlp.util.EditDistance;
import edu.stanford.nlp.util.IString;
import edu.stanford.nlp.util.IStrings;
import mt.base.NBestListContainer;
import mt.base.RawSequence;
import mt.base.ScoredFeaturizedTranslation;
import mt.base.Sequence;
import mt.decoder.recomb.RecombinationFilter;
import mt.decoder.util.State;

public class WERMetric<TK, FV> extends AbstractMetric<TK, FV> {
	final List<List<Sequence<TK>>> referencesList;
	
	public WERMetric(List<List<Sequence<TK>>> referencesList) {
		this.referencesList = referencesList;
	}
	
	@Override
	public WERIncrementalMetric getIncrementalMetric() {
		return new WERIncrementalMetric();
	}

	@Override
	public IncrementalEvaluationMetric<TK, FV> getIncrementalMetric(
			NBestListContainer<TK, FV> nbestList) {
		throw new UnsupportedOperationException();
	}

	@Override
	public RecombinationFilter<IncrementalEvaluationMetric<TK, FV>> getIncrementalMetricRecombinationFilter() {
		throw new UnsupportedOperationException();
	}

	@Override
	public double maxScore() {		
		return 1.0;
	}
	
	public class WERIncrementalMetric implements IncrementalEvaluationMetric<TK,FV> {
		List<Double> wers = new ArrayList<Double>();  
		EditDistance editDistance = new EditDistance();
		double sum = 0;
		
		private double minimumEditDistance(int id, Sequence<TK> seq) {
			Object[] outArr = (new RawSequence<TK>(seq)).elements;
			double minEd = Double.POSITIVE_INFINITY;
			for (Sequence<TK> ref : referencesList.get(id)) {
				Object[] refArr =  (new RawSequence<TK>(ref)).elements;
				double ed = editDistance.score(outArr, refArr);
				if (minEd > ed) minEd = ed;
			}
			return minEd;
		}
		
		@Override
		public IncrementalEvaluationMetric<TK, FV> add(
				ScoredFeaturizedTranslation<TK, FV> trans) {
			int id = wers.size();
			double minEd = minimumEditDistance(id,trans.translation);
			wers.add(-minEd);
			sum += -minEd;
			
			return this;
		}

		@Override
		public double maxScore() {
			return 1.0;
		}

		@Override
		public IncrementalEvaluationMetric<TK, FV> replace(int id,
				ScoredFeaturizedTranslation<TK, FV> trans) {
			double newMinEd = minimumEditDistance(id,trans.translation);
			sum -= wers.get(id);
			sum += newMinEd;
			wers.set(id, newMinEd);
			return this;
		}

		@Override
		public double score() {
			int wersSz = wers.size();
			if (wersSz == 0) return 0;
			return (sum/(wersSz+1));
		}

		@Override
		public int size() {
			return wers.size();
		}

		@Override
		public int compareTo(IncrementalEvaluationMetric<TK, FV> o) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int depth() {
			throw new UnsupportedOperationException();
		}

		@Override
		public State<IncrementalEvaluationMetric<TK, FV>> parent() {
			throw new UnsupportedOperationException();
		}

		@Override
		public double partialScore() {
			throw new UnsupportedOperationException();
		}
		
		public WERIncrementalMetric clone() {
      return new WERIncrementalMetric();
    }
	}
	
	 @SuppressWarnings("unchecked")
	  public static void main(String[] args) throws IOException {
	    if (args.length == 0) {
	      System.err.println("Usage:\n\tjava WERMetric (ref 1) (ref 2) ... (ref n) < canidateTranslations\n");
	      System.exit(-1);
	    }
	    List<List<Sequence<IString>>> referencesList = Metrics.readReferences(args);

	    WERMetric<IString,String> wer = new WERMetric<IString,String>(referencesList);
	    WERMetric<IString,String>.WERIncrementalMetric incMetric = wer.getIncrementalMetric();

	    LineNumberReader reader = new LineNumberReader(new InputStreamReader(System.in));

	    for (String line; (line = reader.readLine()) != null; ) {
	      line = NISTTokenizer.tokenize(line);
	      line = line.replaceAll("\\s+$", "");
	      line = line.replaceAll("^\\s+", "");
	      Sequence<IString> translation = new RawSequence<IString>(IStrings.toIStringArray(line.split("\\s+")));
	      ScoredFeaturizedTranslation<IString, String> tran = new ScoredFeaturizedTranslation<IString, String>(translation, null, 0);
	      incMetric.add(tran);
	    }

	    reader.close();

	    System.out.printf("WER = %.3f\n", 100*incMetric.score());
	  }
}