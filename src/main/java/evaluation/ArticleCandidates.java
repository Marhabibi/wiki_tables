package evaluation;

import datastruct.wikitable.WikiColumnHeader;
import datastruct.wikitable.WikiTable;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.map.hash.TIntDoubleHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import io.FileUtils;
import representation.CategoryRepresentation;
import utils.DataUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Compute the relatedness between Wikipedia articles based on their category representation and other features we consider
 * in our approach for generating candidates.
 * Created by besnik on 12/6/17.
 */
public class ArticleCandidates {
    //the entity dictionaries which include the entities for which we have the ground-truth (seed_entities, gt_entities),
    // and the entities which contain a table (filter_entities)
    public static Set<String> seed_entities = new HashSet<>();
    public static Set<String> filter_entities = new HashSet<>();
    public static Map<String, Set<String>> gt_entities = new HashMap<>();

    //the entity categories
    public static Map<String, Set<String>> entity_cats = new HashMap<>();
    public static Map<String, Set<String>> cats_entities = new HashMap<>();

    //load the word  and graph embeddings.
    public static Map<String, TDoubleArrayList> word2vec;
    public static Map<String, TDoubleArrayList> node2vec;

    //load the wikipedia tables
    public Map<String, Map<String, List<WikiTable>>> tables;

    //keep the set of entity pairs that are not computed.
    public static Set<String> finished_gt_seeds = new HashSet<>();

    //keep the entity abstracts
    public static Map<String, String> entity_abstracts = new HashMap<>();
    public static Map<String, Map<String, Double>> tfidfscores = new HashMap<>();
    public static Map<String, Set<String>> first_words = new HashMap<>();

    //keep the cat similarities
    public static TIntObjectHashMap<TIntDoubleHashMap> cat_sim;

    //load the list of stop words
    public static Set<String> stop_words;

    //load the category representation object
    public static CategoryRepresentation cat;
    public static Map<String, CategoryRepresentation> cat_map;
    public static Map<String, Set<String>> cat_parents;

    public static void main(String[] args) throws IOException, InterruptedException {
        String out_dir = "", table_data = "", option = "", feature_file = "", filter_tag = "";
        double sim_threshold = 0.0;
        int filter_index = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-out_dir")) {
                out_dir = args[++i];
            } else if (args[i].equals("-article_categories")) {
                String article_cats = args[++i];
                entity_cats = DataUtils.readEntityCategoryMappingsWiki(article_cats, null);
                cats_entities = DataUtils.readCategoryMappingsWiki(article_cats, null);
            } else if (args[i].equals("-seed_entities")) {
                seed_entities = FileUtils.readIntoSet(args[++i], "\n", false);
            } else if (args[i].equals("-filter_entities")) {
                filter_entities = FileUtils.readIntoSet(args[++i], "\n", false);
            } else if (args[i].equals("-gt_pairs")) {
                gt_entities = FileUtils.readMapSet(args[++i], "\t");
            } else if (args[i].equals("-table_data")) {
                table_data = args[++i];
            } else if (args[i].equals("-word2vec")) {
                word2vec = DataUtils.loadWord2Vec(args[++i]);
            } else if (args[i].equals("-graph_emb")) {
                node2vec = DataUtils.loadWord2Vec(args[++i]);
            } else if (args[i].equals("-finished_gt_seeds")) {
                finished_gt_seeds = FileUtils.readIntoSet(args[++i], "\n", false);
            } else if (args[i].equals("-abstracts")) {
                entity_abstracts = DataUtils.loadEntityAbstracts(args[++i]);
            } else if (args[i].equals("-tfidf")) {
                tfidfscores = DataUtils.loadEntityTFIDFSim(args[++i]);
            } else if (args[i].equals("-cat_sim")) {
                cat_sim = DataUtils.loadCategoryRepSim(args[++i]);
            } else if (args[i].equals("-option")) {
                option = args[++i];
            } else if (args[i].equals("-sim_threshold")) {
                sim_threshold = Double.parseDouble(args[++i]);
            } else if (args[i].equals("-features")) {
                feature_file = args[++i];
            } else if (args[i].equals("-stop_words")) {
                stop_words = FileUtils.readIntoSet(args[++i], "\n", false);
            } else if (args[i].equals("-filter_index")) {
                filter_index = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-filter_tag")) {
                filter_tag = args[++i];
            } else if (args[i].equals("-cat_rep")) {
                cat = CategoryRepresentation.readCategoryGraph(args[++i]);
                cat_map = new HashMap<>();
                cat.loadIntoMapChildCats(cat_map);

                //load the parents of each category
                cat_parents = new HashMap<>();
                cat_map.keySet().forEach(cat -> cat_parents.put(cat, new HashSet<>()));
                cat_map.keySet().parallelStream().forEach(cat -> DataUtils.gatherParents(cat_map.get(cat), cat_parents.get(cat)));

                System.out.println("Loaded the category representation object and constructed the parent paths for each category");
            }
        }
        ArticleCandidates ac = new ArticleCandidates();

        if (option.equals("extract_features")) {
            ac.tables = DataUtils.loadTables(table_data, filter_entities, false);
            System.out.printf("Finished loading the data tables for %d entities.\n", ac.tables.size());
            //compute the features.
            ac.scoreTableCandidatesApproach(out_dir);
        } else if (option.equals("filter_features")) {
            ac.filterFeaturesByEntityEmb(feature_file, sim_threshold, filter_index, filter_tag, out_dir);
        }
    }

    /**
     * Filter the feature file which contains all possible entity pairs. Here we filter the entity pairs correspondingly
     * the feature file based on the entity similarity based on their embeddings.
     *
     * @param feature_file
     */
    public void filterFeaturesByEntityEmb(String feature_file, double sim_threshold, int filter_index, String filter_tag, String out_dir) throws IOException {
        BufferedReader reader = FileUtils.getFileReader(feature_file);
        String line;

        StringBuffer sb = new StringBuffer();
        DecimalFormat df = new DecimalFormat("#.0");
        String out_file = out_dir + "/entity_" + filter_tag + "_features_" + sim_threshold + ".tsv";
        int line_counter = 0;
        int total = 0, true_total = 0;
        while ((line = reader.readLine()) != null) {
            String[] data = line.split("\t");

            if (line_counter == 0) {
                FileUtils.saveText(line + "\n", out_file);
                line_counter++;
                continue;
            }

            //we filter by the embedding similarity between the entity pair. the similarity is in the 8th column
            double sim = Double.parseDouble(df.format(Double.parseDouble(data[filter_index])));
            if (sim >= sim_threshold) {
                sb.append(line).append("\n");
                total++;

                if (line.endsWith("true")) {
                    true_total++;
                }
            }

            if (sb.length() > 10000) {
                FileUtils.saveText(sb.toString(), out_file, true);
                sb.delete(0, sb.length());
            }
        }

        FileUtils.saveText(sb.toString(), out_file, true);
        System.out.printf("Finished filtering the main feature file based on the %s similarity with a threshold of %.2f.\nThere are in total %d matching instances out of which %d are true.\n", filter_tag, sim_threshold, total, true_total);
    }

    /**
     * Generates the feature representation for each of our ground-truth articles based on our approach which consists
     * of several features.
     *
     * @param out_dir
     */
    public void scoreTableCandidatesApproach(String out_dir) throws IOException {
        StringBuffer header = new StringBuffer();
        header.append("entity_a\tentity_b\tmin_cat_rep_sim\tmax_cat_rep_sim\tmean_cat_rep_sim\t");
        header.append("cat_entity_overlap\tmin_cat_level_diff\tmax_cat_level_diff\tmean_cat_level_diff\t");
        header.append("cat_a_parent_level_diff\tcat_b_parent_level_diff\tcat_a_parent_entity_overlap\tcat_b_parent_entity_overlap\t");
        header.append("same_cats\toverlap_cats_no\tentity_tfidf_sim\tjacc_firstwords_sim\tw2v_firstwords_sim\t");
        header.append("title_jacc_sim\ttitle_overlap_sim_a\ttitle_overlap_sim_b\t");
        header.append("section_w2v_sim\tmin_distance_col\tmax_distance_col\t");
        header.append("mean_distance_col\tmin_col_w2v_sim\tmax_col_w2v_sim\tmean_col_w2v_sim\t");
        header.append("cat_n2v_min_sim\tcat_n2v_max_sim\tcat_n2v_mean_sim\te_n2v_ec\tavg_n2v_cat_sim\tabs_sim\tlabel\n");
        FileUtils.saveText(header.toString(), out_dir + "/candidate_features.tsv");

        //since we reuse the average word vectors we create them first and then reuse.
        Map<String, TDoubleArrayList> avg_w2v = new HashMap<>();
        filter_entities.stream().forEach(entity -> avg_w2v.put(entity, DataUtils.computeAverageWordVector(entity_abstracts.get(entity), stop_words, word2vec)));
        //compute the average vectors of the categories of an entity.
        Map<String, TDoubleArrayList> avg_cat_n2v = new HashMap<>();
        filter_entities.stream().forEach(entity -> avg_cat_n2v.put(entity, DataUtils.computeGraphAverageEmbedding(entity_cats.get(entity), node2vec)));

        loadFirsWordsMaps();

        //filter the filter entities to only those that contain tables.
        filter_entities = filter_entities.stream().filter(e -> tables.containsKey(e)).collect(Collectors.toSet());
        for (String entity : seed_entities) {
            if (finished_gt_seeds.contains(entity)) {
                continue;
            }
            System.out.printf("Processing entity %s\n", entity);
            TDoubleArrayList entity_abs_w2v_avg = avg_w2v.get(entity);
            Set<String> entity_cats_a = entity_cats.get(entity);
            List<String> feature_lines = new ArrayList<>();
            List<String> concurrent_fl = Collections.synchronizedList(feature_lines);

            TDoubleArrayList entity_emb = node2vec.get(entity.replaceAll(" ", "_"));

            long time = System.nanoTime();
            filter_entities.parallelStream().forEach(entity_candidate -> {
                TDoubleArrayList entity_candidate_emb = node2vec.get(entity_candidate.replaceAll(" ", "_"));

                StringBuffer sb = new StringBuffer();
                TDoubleArrayList entity_candidate_abs_w2v_avg = avg_w2v.get(entity_candidate);
                Set<String> entity_cats_candidate = entity_cats.get(entity_candidate);

                //create for each of these pairs the features
                boolean label = gt_entities.containsKey(entity) && gt_entities.get(entity).contains(entity_candidate);

                //add all the category representation similarities
                double[] cat_rep_sim = computeCategorySim(entity_cats_a, entity_cats_candidate);
                double[] lca_cat_sim = computeLCACats(entity_cats_a, entity_cats_candidate);

                boolean same_cats = entity_cats_a.equals(entity_cats_candidate);
                int common_cats = 0;
                if (entity_cats_a != null && entity_cats_candidate != null) {
                    Set<String> cats_common = new HashSet<>(entity_cats_a);
                    cats_common.retainAll(entity_cats_candidate);
                    common_cats = cats_common.size();
                }

                //compute the abstract and the first 10 word similarity between entities.
                double score = tfidfscores.containsKey(entity) && tfidfscores.get(entity).containsKey(entity_candidate) ? tfidfscores.get(entity).get(entity_candidate) : 0.0;
                double[] firstwords_sim = computeFirstWordsSentenceSimilarity(entity, entity_candidate);

                //compute the title similarities
                double[] entity_title_sim = computeEntityTitleSim(entity, entity_candidate);

                //add all the table column similarities
                double[] tbl_sim = computeTableFeatures(entity, entity_candidate);

                //add all the node2vec similarities for the instances in the table
                double[] cat_emb_sim = computeCategoryEmbeddSim(entity_cats_a, entity_cats_candidate);
                double e_n2v_ec = DataUtils.computeCosineSim(entity_emb, entity_candidate_emb);
                double avg_cat_n2v_sim = DataUtils.computeCosineSim(avg_cat_n2v.get(entity), avg_cat_n2v.get(entity_candidate));

                //compute the w2v sim between the entity abstracts
                double abs_sim = DataUtils.computeCosineSim(entity_abs_w2v_avg, entity_candidate_abs_w2v_avg);

                //add the features.
                sb.append(entity).append("\t").append(entity_candidate).append("\t");
                IntStream.range(0, cat_rep_sim.length).forEach(i -> sb.append(cat_rep_sim[i]).append("\t"));
                IntStream.range(0, lca_cat_sim.length).forEach(i -> sb.append(lca_cat_sim[i]).append("\t"));
                sb.append(same_cats).append("\t").append(common_cats).append("\t").append(score).append("\t").append(firstwords_sim[0]).append("\t").append(firstwords_sim[1]).append("\t");
                IntStream.range(0, entity_title_sim.length).forEach(i -> sb.append(entity_title_sim[i]).append("\t"));
                IntStream.range(0, tbl_sim.length).forEach(i -> sb.append(tbl_sim[i]).append("\t"));
                IntStream.range(0, cat_emb_sim.length).forEach(i -> sb.append(cat_emb_sim[i]).append("\t"));
                sb.append(e_n2v_ec).append("\t").append(avg_cat_n2v_sim).append("\t");
                sb.append(abs_sim).append("\t").append(label).append("\n");

                concurrent_fl.add(sb.toString());
            });

            StringBuffer sb = new StringBuffer();
            for (String line : concurrent_fl) {
                sb.append(line);
                if (sb.length() > 10000) {
                    FileUtils.saveText(sb.toString(), out_dir + "/candidate_features.tsv", true);
                    sb.delete(0, sb.length());
                }
            }
            time = System.nanoTime() - time;
            FileUtils.saveText(sb.toString(), out_dir + "/candidate_features.tsv", true);
            System.out.printf("Finished processing features for entity %s.\n", entity);

            FileUtils.saveText(entity + "\n", "finished_entities.tsv", true);
        }
    }

    /**
     * Compute the category representation similarity between the categories of two entities.
     *
     * @param cats_a
     * @param cats_b
     * @return
     */
    public double[] computeCategorySim(Set<String> cats_a, Set<String> cats_b) {
        List<Double> scores = new ArrayList<>();
        List<Integer> level_diff = new ArrayList<>();

        if (cats_a == null || cats_b == null) {
            return new double[]{-1, -1, -1, 0.0, -1, -1, -1};
        }

        Set<String> entities_a = new HashSet<>();
        Set<String> entities_b = new HashSet<>();
        for (String cat_a : cats_a) {
            if (cats_entities.containsKey(cat_a)) {
                entities_a.addAll(cats_entities.get(cat_a));
            }

            int cat_a_hash = cat_a.hashCode();
            for (String cat_b : cats_b) {
                if (cats_entities.containsKey(cat_b)) {
                    entities_b.addAll(cats_entities.get(cat_b));
                }
                int cat_b_hash = cat_b.hashCode();

                if (cat_sim.containsKey(cat_a_hash) && cat_sim.get(cat_a_hash).containsKey(cat_b_hash)) {
                    double score = cat_sim.get(cat_a_hash).get(cat_b_hash);
                    scores.add(score);
                }
                if (cat_map.containsKey(cat_a) && cat_map.containsKey(cat_b)) {
                    level_diff.add(Math.abs(cat_map.get(cat_a).level - cat_map.get(cat_b).level));
                }
            }
        }

        double entity_overlap = DataUtils.computeJaccardSimilarity(entities_a, entities_b);
        double[] results = new double[7];
        if (!scores.isEmpty()) {
            results[0] = scores.stream().mapToDouble(x -> x).min().getAsDouble();
            results[1] = scores.stream().mapToDouble(x -> x).max().getAsDouble();
            results[2] = scores.stream().mapToDouble(x -> x).average().getAsDouble();
        }
        results[3] = entity_overlap;
        if (!level_diff.isEmpty()) {
            results[4] = level_diff.stream().mapToDouble(x -> x).min().getAsDouble();
            results[5] = level_diff.stream().mapToDouble(x -> x).max().getAsDouble();
            results[6] = level_diff.stream().mapToDouble(x -> x).average().getAsDouble();
        }
        return results;
    }

    /**
     * Checks the difference in terms of categories directly associated to the pair of entities and their
     * corresponding LCA categories. We measure their difference in terms of levels and additionally
     * their entity overlap.
     *
     * @param cats_a
     * @param cats_b
     * @return
     */
    public static double[] computeLCACats(Set<String> cats_a, Set<String> cats_b) {
        Set<String> lca_cats = DataUtils.findLCACategories(cats_a, cats_b, cat_parents, cat_map);
        if (lca_cats == null || lca_cats.isEmpty()) {
            return new double[]{100, 100, 0.0, 0.0};
        }

        int min_diff_a = 100, min_diff_b = 100;
        String cat_min_diff_a = "", cat_min_diff_b = "", lca_cat_min_a = "", lca_cat_min_b = "";
        for (String lca_cat : lca_cats) {
            if (!cat_map.containsKey(lca_cat)) {
                continue;
            }
            int lca_level = cat_map.get(lca_cat).level;

            for (String cat_a : cats_a) {
                if (!cat_map.containsKey(cat_a)) {
                    continue;
                }
                int cat_a_level = cat_map.get(cat_a).level;
                int tmp_diff_a = Math.abs(cat_a_level - lca_level);

                if (tmp_diff_a < min_diff_a) {
                    min_diff_a = tmp_diff_a;
                    cat_min_diff_a = cat_a;
                    lca_cat_min_a = lca_cat;
                }
            }
            for (String cat_b : cats_b) {
                if (!cat_map.containsKey(cat_b)) {
                    continue;
                }
                int cat_b_level = cat_map.get(cat_b).level;
                int tmp_diff_b = Math.abs(cat_b_level - lca_level);

                if (tmp_diff_b < min_diff_b) {
                    min_diff_b = tmp_diff_b;
                    cat_min_diff_b = cat_b;
                    lca_cat_min_b = lca_cat;
                }
            }
        }

        //compute the entity overlap
        double overlap_a = DataUtils.computeJaccardSimilarity(cats_entities.get(lca_cat_min_a), cats_entities.get(cat_min_diff_a));
        double overlap_b = DataUtils.computeJaccardSimilarity(cats_entities.get(lca_cat_min_b), cats_entities.get(cat_min_diff_b));

        double[] result = new double[4];
        result[0] = min_diff_a;
        result[1] = min_diff_b;
        result[2] = overlap_a;
        result[3] = overlap_b;

        return result;
    }

    /**
     * Compute the similarity of categories assigned to entities A and B based on their graph embeddings.
     *
     * @param cats_a
     * @param cats_b
     * @return
     */
    public double[] computeCategoryEmbeddSim(Set<String> cats_a, Set<String> cats_b) {
        if (cats_a == null || cats_b == null) {
            return new double[3];
        }

        List<Double> scores = new ArrayList<>();
        int num_dimensions = 256;
        for (String cat_a : cats_a) {
            TDoubleArrayList cat_a_embedd = node2vec.get(cat_a.replaceAll(" ", "_"));
            if (cat_a_embedd == null) {
                continue;
            }
            for (String cat_b : cats_b) {
                TDoubleArrayList cat_b_embedd = node2vec.get(cat_b.replaceAll(" ", "_"));
                if (cat_b_embedd == null) {
                    continue;
                }

                //compute the cosine similarity
                double score = 0;
                for (int i = 0; i < num_dimensions; i++) {
                    score += cat_a_embedd.get(i) * cat_b_embedd.get(i);
                }
                double sum_a = Math.sqrt(Arrays.stream(cat_a_embedd.toArray()).map(x -> Math.pow(x, 2)).sum());
                double sum_b = Math.sqrt(Arrays.stream(cat_b_embedd.toArray()).map(x -> Math.pow(x, 2)).sum());

                score /= (sum_a * sum_b);
                scores.add(score);
            }
        }

        if (scores.isEmpty()) {
            return new double[3];
        }

        double min = scores.stream().mapToDouble(x -> x).min().getAsDouble();
        double max = scores.stream().mapToDouble(x -> x).max().getAsDouble();
        double mean = scores.stream().mapToDouble(x -> x).average().getAsDouble();
        return new double[]{min, max, mean};
    }

    /**
     * Compute the similarity features between two entities in terms of their tables, respectively the table schemas.
     *
     * @param entity_a
     * @param entity_b
     * @return
     */
    public double[] computeTableFeatures(String entity_a, String entity_b) {
        Map<String, List<WikiTable>> tables_a = tables.get(entity_a);
        Map<String, List<WikiTable>> tables_b = tables.get(entity_b);

        List<Double> table_schema_pos = new ArrayList<>();
        List<Double> table_schema_sim = new ArrayList<>();

        //compute the cross-table match.
        Map<Integer, Map.Entry<Integer, Double>> max_match = null;
        double section_sim = 0;
        for (String section_a : tables_a.keySet()) {
            for (WikiTable table_a : tables_a.get(section_a)) {
                for (String section_b : tables_b.keySet()) {
                    //check which is the highest matching table for table_a. The comparison is done in the amount of
                    //columns that have high matching column names and close index.
                    for (WikiTable table_b : tables_b.get(section_b)) {
                        Map<Integer, Map.Entry<Integer, Double>> tbl_match = computeTableSchemaSimilarity(table_a, table_b);

                        if (max_match == null || isBetterMatch(max_match, tbl_match)) {
                            max_match = tbl_match;
                            section_sim = computeWord2VecSim(section_a, section_b);
                        }
                    }
                }
            }
        }

        //add the features which correspond to col idx differences and col name match
        for (int col_idx : max_match.keySet()) {
            Map.Entry<Integer, Double> match = max_match.get(col_idx);
            table_schema_pos.add(Math.abs((double) (col_idx - match.getKey())));
            table_schema_sim.add(match.getValue());
        }

        double min_distance = table_schema_pos.stream().mapToDouble(x -> x).min().getAsDouble();
        double max_distance = table_schema_pos.stream().mapToDouble(x -> x).max().getAsDouble();
        double mean_distance = table_schema_pos.stream().mapToDouble(x -> x).average().getAsDouble();

        double min_sim = table_schema_sim.stream().mapToDouble(x -> x).min().getAsDouble();
        double max_sim = table_schema_sim.stream().mapToDouble(x -> x).max().getAsDouble();
        double mean_sim = table_schema_sim.stream().mapToDouble(x -> x).average().getAsDouble();

        return new double[]{section_sim, min_distance, max_distance, mean_distance, min_sim, max_sim, mean_sim};
    }

    /**
     * Check which of the table matches is a better one for a given table.
     *
     * @param match_a
     * @param match_b
     * @return
     */
    public boolean isBetterMatch(Map<Integer, Map.Entry<Integer, Double>> match_a, Map<Integer, Map.Entry<Integer, Double>> match_b) {
        int better = 0;

        for (int col_idx : match_a.keySet()) {
            if (!match_b.containsKey(col_idx)) {
                continue;
            }

            Map.Entry<Integer, Double> col_a_match = match_a.get(col_idx);
            Map.Entry<Integer, Double> col_b_match = match_b.get(col_idx);

            boolean is_better_col_idx = col_b_match.getKey() < col_a_match.getKey();
            boolean is_better_sim_match = col_b_match.getValue() > col_a_match.getValue();

            if (is_better_sim_match) {
                if (is_better_col_idx || Math.abs(col_a_match.getKey() - col_b_match.getKey()) <= 2) {
                    better++;
                }
            }
        }

        return better > (match_a.size() / 2.0);
    }

    /**
     * Compute the similarity of column names between the two tables.
     *
     * @param a
     * @param b
     */
    public Map<Integer, Map.Entry<Integer, Double>> computeTableSchemaSimilarity(WikiTable a, WikiTable b) {
        WikiColumnHeader[] cols_a = a.columns[a.columns.length - 1];
        WikiColumnHeader[] cols_b = b.columns[b.columns.length - 1];

        Map<Integer, Map.Entry<Integer, Double>> scores = new HashMap<>();
        for (int i = 0; i < cols_a.length; i++) {
            double max_match = 0.0;
            int max_indice = cols_b.length - 1;
            WikiColumnHeader col_a = cols_a[i];
            for (int j = 0; j < cols_b.length; j++) {
                WikiColumnHeader col_b = cols_b[j];

                double sim = computeWord2VecSim(col_a.column_name, col_b.column_name);
                if (sim > max_match) {
                    max_match = sim;
                    max_indice = j;
                }
            }
            scores.put(i, new AbstractMap.SimpleEntry<>(max_indice, max_match));
        }
        return scores;
    }

    /**
     * Compute the similarity between two strings based on their word vector representation.
     *
     * @param str_a
     * @param str_b
     * @return
     */
    public double computeWord2VecSim(String str_a, String str_b) {
        //compute average word vectors
        TDoubleArrayList avg_a = DataUtils.computeAverageWordVector(str_a, word2vec);
        TDoubleArrayList avg_b = DataUtils.computeAverageWordVector(str_b, word2vec);
        return DataUtils.computeCosineSim(avg_a, avg_b);
    }

    /**
     * We check if the sentences contain similar phrases that might reveal that the entities are of the same topic
     * or talk about similar concepts.
     *
     * @param entity_a
     * @param entity_b
     */
    public static double[] computeFirstWordsSentenceSimilarity(String entity_a, String entity_b) {
        Set<String> first_words_a = first_words.get(entity_a);
        Set<String> first_words_b = first_words.get(entity_b);

        //compute the jaccard similarity and the cosine similarity based on the word embeddings.
        double jacc_sim = DataUtils.computeJaccardSimilarity(first_words_a, first_words_b);

        TDoubleArrayList w2v_a = DataUtils.computeAverageWordVector(first_words_a, word2vec);
        TDoubleArrayList w2v_b = DataUtils.computeAverageWordVector(first_words_b, word2vec);
        double cosine_sim = DataUtils.computeCosineSim(w2v_a, w2v_b);
        return new double[]{jacc_sim, cosine_sim};
    }


    /**
     * Load the first 10 words from each entity abstract.
     */
    public static void loadFirsWordsMaps() {
        filter_entities.stream().forEach(entity -> {
            if (entity_abstracts.containsKey(entity)) {
                String[] words = entity_abstracts.get(entity).toLowerCase().split("\\s+");
                Set<String> words_set = new HashSet<>();
                for (String word : words) {
                    if (stop_words.contains(word)) {
                        continue;
                    }
                    if (words_set.size() > 10) {
                        break;
                    }
                    words_set.add(word);
                }
                first_words.put(entity, words_set);
            }
        });
    }


    /**
     * Compute the similarity between the entity titles and the occurrence of title tokens in the abstract.
     *
     * @param entity_a
     * @param entity_b
     * @return
     */
    public static double[] computeEntityTitleSim(String entity_a, String entity_b) {
        String abstract_a = entity_abstracts.containsKey(entity_a) ? entity_abstracts.get(entity_a) : "";
        String abstract_b = entity_abstracts.containsKey(entity_b) ? entity_abstracts.get(entity_b) : "";

        //generate the sets from the titles
        Set<String> entity_a_tokens = new HashSet<>(Arrays.asList(entity_a.toLowerCase().split("\\s+")));
        Set<String> entity_b_tokens = new HashSet<>(Arrays.asList(entity_b.toLowerCase().split("\\s+")));

        double title_sim_jacc = DataUtils.computeJaccardSimilarity(entity_a_tokens, entity_b_tokens);

        double title_abs_a_overlap = entity_a_tokens.stream().mapToDouble(k -> abstract_b.contains(k) ? 1 : 0).sum() / entity_a_tokens.size();
        double title_abs_b_overlap = entity_b_tokens.stream().mapToDouble(k -> abstract_a.contains(k) ? 1 : 0).sum() / entity_b_tokens.size();

        return new double[]{title_sim_jacc, title_abs_a_overlap, title_abs_b_overlap};
    }


}