package com.jstarcraft.recommendation.recommender.collaborative.ranking;

import java.util.HashMap;
import java.util.List;

import com.jstarcraft.ai.math.structure.MathCalculator;
import com.jstarcraft.ai.math.structure.matrix.DenseMatrix;
import com.jstarcraft.ai.math.structure.vector.DenseVector;
import com.jstarcraft.ai.utility.MathUtility;
import com.jstarcraft.recommendation.recommender.MatrixFactorizationRecommender;
import com.jstarcraft.recommendation.utility.LogisticUtility;

import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Shi et al., <strong>Climf: learning to maximize reciprocal rank with
 * collaborative less-is-more filtering.</strong>, RecSys 2012.
 *
 * @author Guibing Guo, Chen Ma and Keqiang Wang
 */
/**
 * 
 * Random Guess推荐器
 * 
 * <pre>
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
public class CLIMFRecommender extends MatrixFactorizationRecommender {

	@Override
	protected void doPractice() {
		List<IntSet> userItemSet = getUserItemSet(trainMatrix);

		float[] factorValues = new float[numberOfFactors];

		for (int iterationStep = 1; iterationStep <= numberOfEpoches; iterationStep++) {
			totalLoss = 0F;
			for (int userIndex = 0; userIndex < numberOfUsers; userIndex++) {
				// TODO 此处应该考虑重构,不再使用itemSet
				IntSet itemSet = userItemSet.get(userIndex);

				// 缓存预测值
				DenseVector predictVector = DenseVector.valueOf(itemSet.size());
				DenseVector logisticVector = DenseVector.valueOf(itemSet.size());
				int index = 0;
				for (int itemIndex : itemSet) {
					float value = predict(userIndex, itemIndex);
					predictVector.setValue(index, value);
					logisticVector.setValue(index, LogisticUtility.getValue(-value));
					index++;
				}
				DenseMatrix logisticMatrix = DenseMatrix.valueOf(itemSet.size(), itemSet.size());
				DenseMatrix gradientMatrix = DenseMatrix.valueOf(itemSet.size(), itemSet.size());
				gradientMatrix.iterateElement(MathCalculator.SERIAL, (scalar) -> {
					int row = scalar.getRow();
					int column = scalar.getColumn();
					float value = predictVector.getValue(row) - predictVector.getValue(column);
					float logistic = LogisticUtility.getValue(value);
					logisticMatrix.setValue(row, column, logistic);
					float gradient = LogisticUtility.getGradient(value);
					scalar.setValue(gradient);
				});

				for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
					float factorValue = -userRegularization * userFactors.getValue(userIndex, factorIndex);
					int leftIndex = 0;
					for (int itemIndex : itemSet) {
						float itemFactorValue = itemFactors.getValue(itemIndex, factorIndex);
						factorValue += logisticVector.getValue(leftIndex) * itemFactorValue;
						// TODO 此处应该考虑对称性减少迭代次数
						int rightIndex = 0;
						for (int compareIndex : itemSet) {
							if (compareIndex != itemIndex) {
								float compareValue = itemFactors.getValue(compareIndex, factorIndex);
								factorValue += gradientMatrix.getValue(rightIndex, leftIndex) / (1 - logisticMatrix.getValue(rightIndex, leftIndex)) * (itemFactorValue - compareValue);
							}
							rightIndex++;
						}
						leftIndex++;
					}
					factorValues[factorIndex] = factorValue;
				}

				int leftIndex = 0;
				for (int itemIndex : itemSet) {
					float logisticValue = logisticVector.getValue(leftIndex);
					for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
						float userFactorValue = userFactors.getValue(userIndex, factorIndex);
						float itemFactorValue = itemFactors.getValue(itemIndex, factorIndex);
						float judgeValue = 1F;
						float factorValue = judgeValue * logisticValue * userFactorValue - itemRegularization * itemFactorValue;
						// TODO 此处应该考虑对称性减少迭代次数
						int rightIndex = 0;
						for (int compareIndex : itemSet) {
							if (compareIndex != itemIndex) {
								factorValue += gradientMatrix.getValue(rightIndex, leftIndex) * (judgeValue / (judgeValue - logisticMatrix.getValue(rightIndex, leftIndex)) - judgeValue / (judgeValue - logisticMatrix.getValue(leftIndex, rightIndex))) * userFactorValue;
							}
							rightIndex++;
						}
						itemFactors.shiftValue(itemIndex, factorIndex, learnRate * factorValue);
					}
					leftIndex++;
				}

				for (int factorIdx = 0; factorIdx < numberOfFactors; factorIdx++) {
					userFactors.shiftValue(userIndex, factorIdx, learnRate * factorValues[factorIdx]);
				}

				// TODO 获取预测值
				HashMap<Integer, Float> predictMap = new HashMap<>(itemSet.size());
				for (int itemIndex : itemSet) {
					float predictValue = predict(userIndex, itemIndex);
					predictMap.put(itemIndex, predictValue);
				}
				for (int itemIndex = 0; itemIndex < numberOfItems; itemIndex++) {
					if (itemSet.contains(itemIndex)) {
						float predictValue = predictMap.get(itemIndex);
						totalLoss += (float) Math.log(LogisticUtility.getValue(predictValue));
						// TODO 此处应该考虑对称性减少迭代次数
						for (int compareIndex : itemSet) {
							float compareValue = predictMap.get(compareIndex);
							totalLoss += (float) Math.log(1 - LogisticUtility.getValue(compareValue - predictValue));
						}
					}
					for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
						float userFactorValue = userFactors.getValue(userIndex, factorIndex);
						float itemFactorValue = itemFactors.getValue(itemIndex, factorIndex);
						totalLoss += -0.5 * (userRegularization * userFactorValue * userFactorValue + itemRegularization * itemFactorValue * itemFactorValue);
					}
				}
			}

			if (isConverged(iterationStep) && isConverged) {
				break;
			}
			isLearned(iterationStep);
			currentLoss = totalLoss;
		}
	}

}
