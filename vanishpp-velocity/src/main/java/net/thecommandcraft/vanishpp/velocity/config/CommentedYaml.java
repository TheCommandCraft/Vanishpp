package net.thecommandcraft.vanishpp.velocity.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Changes values in a YAML document and keeps everything else as written: the help comments, key order and
 * quoting. A plain load and dump would drop every comment (RFer's report: settings changed in game wiped them).
 */
final class CommentedYaml {

    private CommentedYaml() {}

    /** Returns {@code text} with each dotted key set to its value; missing keys and sections are added. */
    static String set(String text, Map<String, Object> values) {
        LoaderOptions loader = new LoaderOptions();
        loader.setProcessComments(true);
        DumperOptions dumper = new DumperOptions();
        dumper.setProcessComments(true);
        dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumper.setIndent(2);
        dumper.setWidth(Integer.MAX_VALUE);
        Yaml yaml = new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);

        Node root = yaml.compose(new StringReader(text));
        if (!(root instanceof MappingNode)) root = new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
        for (Map.Entry<String, Object> e : values.entrySet())
            put((MappingNode) root, e.getKey().split("\\."), 0, yaml.represent(e.getValue()));
        StringWriter out = new StringWriter();
        yaml.serialize(root, out);
        return out.toString();
    }

    private static void put(MappingNode map, String[] path, int at, Node value) {
        List<NodeTuple> tuples = map.getValue();
        for (int i = 0; i < tuples.size(); i++) {
            NodeTuple tuple = tuples.get(i);
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || !key.getValue().equals(path[at])) continue;
            if (at == path.length - 1) {
                Node old = tuple.getValueNode();
                value.setInLineComments(old.getInLineComments()); // "enabled: true  # explanation" keeps its note
                value.setEndComments(old.getEndComments());
                tuples.set(i, new NodeTuple(tuple.getKeyNode(), value));
            } else {
                MappingNode section;
                if (tuple.getValueNode() instanceof MappingNode existing) section = existing;
                else {
                    section = new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
                    tuples.set(i, new NodeTuple(tuple.getKeyNode(), section));
                }
                put(section, path, at + 1, value);
            }
            return;
        }
        Node child = value;
        for (int j = path.length - 1; j > at; j--) {
            List<NodeTuple> single = new ArrayList<>();
            single.add(new NodeTuple(new ScalarNode(Tag.STR, path[j], null, null, DumperOptions.ScalarStyle.PLAIN), child));
            child = new MappingNode(Tag.MAP, single, DumperOptions.FlowStyle.BLOCK);
        }
        tuples.add(new NodeTuple(new ScalarNode(Tag.STR, path[at], null, null, DumperOptions.ScalarStyle.PLAIN), child));
    }
}
