package com.jstarcraft.recommendation.recommender.context.rating;

import com.jstarcraft.ai.math.algorithm.similarity.Similarity;
import com.jstarcraft.ai.math.structure.MathCalculator;
import com.jstarcraft.ai.math.structure.matrix.DenseMatrix;
import com.jstarcraft.ai.math.structure.matrix.MatrixScalar;
import com.jstarcraft.ai.math.structure.matrix.SymmetryMatrix;
import com.jstarcraft.ai.math.structure.vector.SparseVector;
import com.jstarcraft.ai.math.structure.vector.VectorScalar;
import com.jstarcraft.core.utility.RandomUtility;
import com.jstarcraft.core.utility.ReflectionUtility;
import com.jstarcraft.recommendation.configure.Configuration;
import com.jstarcraft.recommendation.data.DataSpace;
import com.jstarcraft.recommendation.data.accessor.InstanceAccessor;
import com.jstarcraft.recommendation.data.accessor.SampleAccessor;
import com.jstarcraft.recommendation.recommender.SocialRecommender;

/**
 * 
 * SoReg推荐器
 * 
 * <pre>
 * Recommender systems with social regularization
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
public class SoRegRecommender extends SocialRecommender {

	private SymmetryMatrix socialCorrelations;

	@Override
	public void prepare(Configuration configuration, SampleAccessor marker, InstanceAccessor model, DataSpace space) {
		super.prepare(configuration, marker, model, space);
		userFactors = DenseMatrix.valueOf(numberOfUsers, numberOfFactors);
		userFactors.iterateElement(MathCalculator.SERIAL, (scalar) -> {
			scalar.setValue(RandomUtility.randomFloat(1F));
		});
		itemFactors = DenseMatrix.valueOf(numberOfItems, numberOfFactors);
		itemFactors.iterateElement(MathCalculator.SERIAL, (scalar) -> {
			scalar.setValue(RandomUtility.randomFloat(1F));
		});

		// TODO 修改为配置枚举
		try {
			Class<Similarity> similarityClass = (Class<Similarity>) Class.forName(configuration.getString("rec.similarity.class"));
			Similarity similarity = ReflectionUtility.getInstance(similarityClass);
			socialCorrelations = similarity.makeSimilarityMatrix(socialMatrix, false, configuration.getFloat("rec.similarity.shrinkage", 0F));
		} catch (Exception exception) {
			throw new RuntimeException(exception);
		}

		for (MatrixScalar term : socialCorrelations) {
			float similarity = term.getValue();
			if (similarity == 0F) {
				continue;
			}
			similarity = (1F + similarity) / 2F;
			term.setValue(similarity);
		}
	}

	@Override
	protected void doPractice() {
		for (int iterationStep = 1; iterationStep <= numberOfEpoches; iterationStep++) {
			totalLoss = 0F;
			DenseMatrix userDeltas = DenseMatrix.valueOf(numberOfUsers, numberOfFactors);
			DenseMatrix itemDeltas = DenseMatrix.valueOf(numberOfItems, numberOfFactors);

			// ratings
			for (MatrixScalar term : trainMatrix) {
				int userIndex = term.getRow();
				int itemIndex = term.getColumn();
				float error = predict(userIndex, itemIndex) - term.getValue();
				totalLoss += error * error;
				for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
					float userFactorValue = userFactors.getValue(userIndex, factorIndex);
					float itemFactorValue = itemFactors.getValue(itemIndex, factorIndex);
					userDeltas.shiftValue(userIndex, factorIndex, error * itemFactorValue + userRegularization * userFactorValue);
					itemDeltas.shiftValue(itemIndex, factorIndex, error * userFactorValue + itemRegularization * itemFactorValue);
					totalLoss += userRegularization * userFactorValue * userFactorValue + itemRegularization * itemFactorValue * itemFactorValue;
				}
			}

			// friends
			for (int userIndex = 0; userIndex < numberOfUsers; userIndex++) {
				// out links: F+
				SparseVector trusterVector = socialMatrix.getRowVector(userIndex);
				for (VectorScalar term : trusterVector) {
					int trusterIndex = term.getIndex();
					float trusterSimilarity = socialCorrelations.getValue(userIndex, trusterIndex);
					if (!Float.isNaN(trusterSimilarity)) {
						for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
							float userFactor = userFactors.getValue(userIndex, factorIndex) - userFactors.getValue(trusterIndex, factorIndex);
							userDeltas.shiftValue(userIndex, factorIndex, socialRegularization * trusterSimilarity * userFactor);
							totalLoss += socialRegularization * trusterSimilarity * userFactor * userFactor;
						}
					}
				}

				// in links: F-
				SparseVector trusteeVector = socialMatrix.getColumnVector(userIndex);
				for (VectorScalar term : trusteeVector) {
					int trusteeIndex = term.getIndex();
					float trusteeSimilarity = socialCorrelations.getValue(userIndex, trusteeIndex);
					if (!Float.isNaN(trusteeSimilarity)) {
						for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
							float userFactor = userFactors.getValue(userIndex, factorIndex) - userFactors.getValue(trusteeIndex, factorIndex);
							userDeltas.shiftValue(userIndex, factorIndex, socialRegularization * trusteeSimilarity * userFactor);
							totalLoss += socialRegularization * trusteeSimilarity * userFactor * userFactor;
						}
					}
				}
			}

			// end of for loop
			userFactors.iterateElement(MathCalculator.PARALLEL, (scalar) -> {
				int row = scalar.getRow();
				int column = scalar.getColumn();
				float value = scalar.getValue();
				scalar.setValue(value + userDeltas.getValue(row, column) * -learnRate);
			});
			itemFactors.iterateElement(MathCalculator.PARALLEL, (scalar) -> {
				int row = scalar.getRow();
				int column = scalar.getColumn();
				float value = scalar.getValue();
				scalar.setValue(value + itemDeltas.getValue(row, column) * -learnRate);
			});

			totalLoss *= 0.5D;
			if (isConverged(iterationStep) && isConverged) {
				break;
			}
			isLearned(iterationStep);
			currentLoss = totalLoss;
		}
	}

	@Override
	protected float predict(int userIndex, int itemIndex) {
		float predictRating = super.predict(userIndex, itemIndex);

		if (predictRating > maximumOfScore) {
			predictRating = maximumOfScore;
		} else if (predictRating < minimumOfScore) {
			predictRating = minimumOfScore;
		}

		return predictRating;
	}

	@Override
	public float predict(int[] dicreteFeatures, float[] continuousFeatures) {
		int userIndex = dicreteFeatures[userDimension];
		int itemIndex = dicreteFeatures[itemDimension];
		return predict(userIndex, itemIndex);
	}

}
