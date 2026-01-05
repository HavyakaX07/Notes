
/*
// Definition for a QuadTree node.
class Node {
    public boolean val;
    public boolean isLeaf;
    public Node topLeft;
    public Node topRight;
    public Node bottomLeft;
    public Node bottomRight;

    public Node() {}
    public Node(boolean val, boolean isLeaf) {
        this.val = val;
        this.isLeaf = isLeaf;
    }
    public Node(boolean val, boolean isLeaf, Node topLeft, Node topRight, Node bottomLeft, Node bottomRight) {
        this.val = val;
        this.isLeaf = isLeaf;
        this.topLeft = topLeft;
        this.topRight = topRight;
        this.bottomLeft = bottomLeft;
        this.bottomRight = bottomRight;
    }
}
*/

class Solution {
    public Node construct(int[][] grid) {
        int n = grid.length;          // full side length
        return build(grid, 0, 0, n);  // start at (0,0) with size n
    }

    private Node build(int[][] grid, int r, int c, int n) {
        // Base case: single cell
        if (n == 1) {
            return new Node(grid[r][c] == 1, true);
        }

        // Check if all values in the n×n block are the same
        int first = grid[r][c];
        boolean same = true;
        for (int i = r; i < r + n && same; i++) {
            for (int j = c; j < c + n; j++) {
                if (grid[i][j] != first) {
                    same = false;
                    break;
                }
            }
        }

        // If same all are same then it is leaf node
        if (same) {
            return new Node(first == 1, true);
        }

        int h = n / 2; // half size
        Node tl = build(grid, r,       c,       h);
        Node tr = build(grid, r,       c + h,   h);
        Node bl = build(grid, r + h,   c,       h);
        Node br = build(grid, r + h,   c + h,   h);

        boolean val = tl.val || tr.val || bl.val || br.val; // conventional
        return new Node(val, false, tl, tr, bl, br);
    }
}

