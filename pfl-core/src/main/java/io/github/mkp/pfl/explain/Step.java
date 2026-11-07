package io.github.mkp.pfl.explain;

import io.github.mkp.pfl.bpmn.Node;

/**
 * One numbered step of an explanation.
 *
 * @param number position in reading order, starting at 1
 * @param elementId the BPMN element, so the reader can find it in the diagram
 * @param label the element's name
 * @param actor who does it
 * @param text what happens, in plain words
 */
public record Step(int number, String elementId, String label, Node.Actor actor, String text) {}
