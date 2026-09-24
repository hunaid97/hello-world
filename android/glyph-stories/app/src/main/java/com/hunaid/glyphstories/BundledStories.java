package com.hunaid.glyphstories;

import java.util.Random;

/** A few short public-domain fables (Aesop, retold) so the app works without a connection. */
final class BundledStories {

    private static final String[][] STORIES = {
            {"The Tortoise and the Hare", "Aesop",
                    "A hare laughed at a tortoise for being slow. The tortoise said, let us race and see. "
                            + "The hare shot ahead, and far from the finish he lay down for a nap, sure he could win "
                            + "whenever he liked. The tortoise kept walking, one step after another, never stopping. "
                            + "When the hare woke up he ran as fast as he could, but the tortoise had already crossed "
                            + "the line. Slow and steady wins the race."},
            {"The Fox and the Grapes", "Aesop",
                    "A hungry fox saw ripe grapes hanging from a high vine. He jumped for them again and again, "
                            + "but they were always just out of reach. At last he gave up and walked away with his nose "
                            + "in the air, saying, they were probably sour anyway. It is easy to scorn what you cannot have."},
            {"The Crow and the Pitcher", "Aesop",
                    "A thirsty crow found a pitcher with a little water at the bottom, too low for her beak to reach. "
                            + "She tried to tip it over, but it was too heavy. Then she had an idea. She dropped pebbles "
                            + "into the pitcher one by one, and with each pebble the water rose, until at last she could "
                            + "drink. Little by little does the trick."},
            {"The Lion and the Mouse", "Aesop",
                    "A lion caught a mouse that had run across his paw. The mouse begged to be let go and promised to "
                            + "repay the kindness one day. The lion laughed, but let her go. Weeks later the lion was caught "
                            + "in a hunter's net and roared in anger. The mouse heard him, ran to the net and gnawed through "
                            + "the ropes until he was free. No act of kindness, however small, is ever wasted."},
            {"The Boy Who Cried Wolf", "Aesop",
                    "A shepherd boy grew bored watching the sheep, so he shouted, wolf, wolf, and the villagers ran up "
                            + "the hill to help. There was no wolf, and the boy laughed. He did it again the next day. "
                            + "Then one evening a real wolf came out of the forest. The boy cried wolf with all his might, "
                            + "but nobody came, because nobody believed him any more. A liar is not believed even when "
                            + "he tells the truth."},
    };

    static StoryStore.Story random(Random random) {
        String[] s = STORIES[random.nextInt(STORIES.length)];
        return StoryFetcher.make(s[0], s[1], "Bundled", s[2]);
    }

    private BundledStories() {}
}
